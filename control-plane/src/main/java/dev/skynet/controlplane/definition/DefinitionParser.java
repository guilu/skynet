package dev.skynet.controlplane.definition;

import dev.skynet.controlplane.definition.Problem.Severity;
import dev.skynet.controlplane.definition.StageDefinition.ChildDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.StageDefinition.WorkspaceMode;
import dev.skynet.controlplane.project.AgentPolicy;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Lee el YAML de un workflow (§11) y lo valida: forma, nombres, referencias entre fases y agentes,
 * ciclos, variables de los prompts y lo que el motor aún no ejecuta. Cada problema lleva su línea y
 * su columna. No lanza excepciones por el contenido del documento.
 *
 * <p>Las claves que admite cada objeto son las mismas que las del JSON Schema de {@code protocol}
 * ({@code workflow-definition.schema.json}), que usa el editor de la web; un test lo comprueba.
 */
public final class DefinitionParser {

  /** Tamaño máximo del YAML, en caracteres. */
  public static final int MAX_SOURCE = 256 * 1024;

  static final Set<String> ROOT_KEYS =
      Set.of("id", "version", "name", "description", "inputs", "agents", "stages");
  static final Set<String> INPUT_KEYS = Set.of("type", "required", "default", "description");
  static final Set<String> AGENT_KEYS =
      Set.of(
          "description",
          "prompt",
          "tools",
          "permissionMode",
          "limits",
          "model",
          "provider",
          "output");
  static final Set<String> LIMIT_KEYS = Set.of("maxTurns", "maxBudgetUsd", "timeoutMinutes");
  static final Set<String> STAGE_KEYS =
      Set.of(
          "id",
          "name",
          "description",
          "type",
          "agent",
          "prompt",
          "dependsOn",
          "workspace",
          "workspaceFrom",
          "command",
          "children",
          "condition");
  static final Set<String> CHILD_KEYS = Set.of("id", "agent", "prompt");

  /** Claves de §11 que llegan con hitos posteriores, con el hito que las ejecuta. */
  static final Map<String, String> FUTURE_STAGE_KEYS =
      Map.of(
          "promptTemplate", "S3",
          "outputSchema", "W4",
          "artifacts", "W4",
          "retry", "W6",
          "timeout", "W6");

  /** Variables que puede usar un prompt, además de {@code inputs.<nombre>}. */
  static final List<String> CONTEXT_VARIABLES =
      List.of(
          "workItem.key",
          "workItem.title",
          "workItem.description",
          "workItem.type",
          "workItem.externalRef",
          "project.key",
          "project.name");

  /** Ids que chocarían con rutas de la API ({@code /api/workflows/schema}) o de la web. */
  static final Set<String> RESERVED_KEYS = Set.of("schema", "validate", "new");

  static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,48}");
  static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z]\\w{0,63}");
  static final Pattern TOOL = Pattern.compile("[A-Za-z]\\w*(\\(.+\\))?");
  static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/\\[\\]-]{0,99}");

  /** Campos de la salida estructurada: los leen las condiciones como {@code fase.campo}. */
  static final Pattern OUTPUT_FIELD = Pattern.compile("[A-Za-z]\\w{0,63}");

  static final List<String> OUTPUT_TYPES =
      List.of("boolean", "string", "number", "integer", "array", "object");

  /** Proveedores que llegan con el hito de proveedores, después de W3. */
  static final Set<String> FUTURE_PROVIDERS = Set.of("gemini", "opencode");

  /** Proveedores que el motor ya ejecuta además de Claude Code. */
  static final Set<String> PROVIDERS = Set.of(AgentDefinition.CODEX);

  private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]*)}}");

  private DefinitionParser() {}

  /**
   * Lo que se espera del documento al guardarlo como una versión concreta.
   *
   * @param key clave del workflow, que no puede cambiar; {@code null} si es nuevo
   * @param version número de la versión que se guarda; {@code null} si no se sabe
   */
  public record Expectations(String key, Integer version) {
    public static final Expectations NONE = new Expectations(null, null);
  }

  /**
   * Resultado de leer el documento.
   *
   * @param key clave leída, o {@code null} si no se pudo leer
   * @param definition el workflow, o {@code null} si el documento no tiene la forma básica
   * @param versionSpan posición del valor de {@code version}, para reescribirlo; o {@code null}
   */
  public record Parsed(
      String key, WorkflowDefinition definition, Validation validation, Span versionSpan) {}

  /** Tramo del texto, como índices {@code [start, end)}. */
  public record Span(int start, int end) {}

  public static Parsed parse(String source, Expectations expect) {
    return new Reader(source == null ? "" : source, expect).read();
  }

  /** Copia el YAML cambiando el valor de {@code version}, si lo tiene. */
  public static String withVersion(String source, int version) {
    if (source == null) {
      return null;
    }
    Parsed parsed = parse(source, Expectations.NONE);
    Span span = parsed.versionSpan();
    if (span == null) {
      return source;
    }
    return source.substring(0, span.start()) + version + source.substring(span.end());
  }

  private static final class Reader {

    private final String source;
    private final Expectations expect;
    private final List<Problem> problems = new ArrayList<>();
    private Span versionSpan;

    Reader(String source, Expectations expect) {
      this.source = source;
      this.expect = expect;
    }

    Parsed read() {
      if (source.length() > MAX_SOURCE) {
        error(null, "", "El YAML ocupa más de " + MAX_SOURCE / 1024 + " KB");
        return done(null, null);
      }
      Node root;
      try {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setAllowDuplicateKeys(true);
        options.setCodePointLimit(MAX_SOURCE * 4);
        List<Node> documents = new ArrayList<>();
        new Yaml(new SafeConstructor(options))
            .composeAll(new StringReader(source))
            .forEach(documents::add);
        if (documents.size() > 1) {
          error(documents.get(1), "", "El YAML tiene más de un documento (---): deja solo uno");
          return done(null, null);
        }
        root = documents.isEmpty() ? null : documents.get(0);
      } catch (MarkedYAMLException e) {
        Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
        add(
            Severity.ERROR,
            "",
            mark == null ? null : mark.getLine() + 1,
            mark == null ? null : mark.getColumn() + 1,
            "El YAML no se puede leer: " + yamlProblem(e));
        return done(null, null);
      } catch (YAMLException e) {
        error(null, "", "El YAML no se puede leer: " + e.getMessage());
        return done(null, null);
      }
      if (root == null || isNull(root)) {
        error(null, "", "El documento está vacío: hacen falta al menos `id` y `stages`");
        return done(null, null);
      }
      Map<String, Entry> fields = mapping(root, "", ROOT_KEYS, Map.of());
      if (fields == null) {
        return done(null, null);
      }

      String key = rootKey(root, fields.get("id"));
      version(fields.get("version"));
      String name = text(fields.get("name"), "name");
      String description = text(fields.get("description"), "description");
      List<InputDefinition> inputs = inputs(fields.get("inputs"));
      List<AgentDefinition> agents = agents(fields.get("agents"));
      Entry stagesEntry = fields.get("stages");
      if (stagesEntry == null) {
        error(root, "stages", "Falta `stages`: la lista de fases del workflow");
        return done(key, null);
      }
      List<StageAt> stages = stages(stagesEntry);
      if (stages == null) {
        return done(key, null);
      }
      checkStages(stages, inputs, agents);
      if (key == null) {
        return done(null, null);
      }
      WorkflowDefinition definition =
          new WorkflowDefinition(
              key, name, description, inputs, agents, stages.stream().map(StageAt::stage).toList());
      return done(key, definition);
    }

    private Parsed done(String key, WorkflowDefinition definition) {
      List<Problem> sorted = new ArrayList<>(problems);
      sorted.sort(
          (a, b) -> {
            int la = a.line() == null ? 0 : a.line();
            int lb = b.line() == null ? 0 : b.line();
            if (la != lb) {
              return Integer.compare(la, lb);
            }
            int ca = a.column() == null ? 0 : a.column();
            int cb = b.column() == null ? 0 : b.column();
            return Integer.compare(ca, cb);
          });
      return new Parsed(key, definition, Validation.of(sorted), versionSpan);
    }

    // ---- Raíz ---------------------------------------------------------------------------------

    private String rootKey(Node root, Entry entry) {
      if (entry == null) {
        error(root, "id", "Falta `id`: la clave del workflow, p. ej. `id: revisar-y-corregir`");
        return null;
      }
      String key = text(entry, "id");
      if (key == null) {
        return null;
      }
      if (!KEY.matcher(key).matches()) {
        error(
            entry.value(),
            "id",
            "`"
                + key
                + "` no vale como id: usa de 2 a 49 minúsculas, números y guiones, empezando por"
                + " una letra");
        return null;
      }
      if (RESERVED_KEYS.contains(key)) {
        error(entry.value(), "id", "`" + key + "` está reservado: elige otro id");
        return null;
      }
      if (expect.key() != null && !expect.key().equals(key)) {
        error(
            entry.value(),
            "id",
            "El id de un workflow no se puede cambiar: este es `" + expect.key() + "`");
        return expect.key();
      }
      return key;
    }

    private void version(Entry entry) {
      if (entry == null) {
        return;
      }
      Integer version = integer(entry, "version", 1, Integer.MAX_VALUE);
      if (version == null) {
        return;
      }
      if (entry.value() instanceof ScalarNode scalar) {
        versionSpan = new Span(scalar.getStartMark().getIndex(), scalar.getEndMark().getIndex());
      }
      if (expect.version() != null && !expect.version().equals(version)) {
        warning(
            entry.value(),
            "version",
            "La versión la pone Skynet: esta se guardará como la "
                + expect.version()
                + ", no la "
                + version);
      }
    }

    // ---- Inputs -------------------------------------------------------------------------------

    private List<InputDefinition> inputs(Entry entry) {
      List<InputDefinition> inputs = new ArrayList<>();
      if (entry == null || isNull(entry.value())) {
        return inputs;
      }
      Map<String, Entry> byName = mapping(entry.value(), "inputs", null, Map.of());
      if (byName == null) {
        return inputs;
      }
      byName.forEach(
          (name, input) -> {
            String path = "inputs." + name;
            if (!INPUT_NAME.matcher(name).matches()) {
              error(
                  input.key(),
                  path,
                  "`"
                      + name
                      + "` no vale como nombre de dato: usa letras, números y _ empezando por una"
                      + " letra");
              return;
            }
            Map<String, Entry> fields = mapping(input.value(), path, INPUT_KEYS, Map.of());
            if (fields == null) {
              return;
            }
            String type = "string";
            Entry typeEntry = fields.get("type");
            if (typeEntry != null) {
              String value = text(typeEntry, path + ".type");
              if (value != null && !InputDefinition.TYPES.contains(value)) {
                error(
                    typeEntry.value(),
                    path + ".type",
                    "Tipo `"
                        + value
                        + "` desconocido"
                        + suggestion(value, InputDefinition.TYPES)
                        + ". Admite: "
                        + String.join(", ", InputDefinition.TYPES));
              } else if (value != null) {
                type = value;
              }
            }
            boolean required = bool(fields.get("required"), path + ".required") == Boolean.TRUE;
            Object defaultValue = defaultValue(fields.get("default"), path + ".default", type);
            inputs.add(
                new InputDefinition(
                    name,
                    type,
                    required,
                    defaultValue,
                    text(fields.get("description"), path + ".description")));
          });
      return inputs;
    }

    private Object defaultValue(Entry entry, String path, String type) {
      if (entry == null || isNull(entry.value())) {
        return null;
      }
      return switch (type) {
        case "number" -> decimal(entry, path, null, null);
        case "boolean" -> bool(entry, path);
        default -> text(entry, path);
      };
    }

    // ---- Agentes ------------------------------------------------------------------------------

    private List<AgentDefinition> agents(Entry entry) {
      List<AgentDefinition> agents = new ArrayList<>();
      if (entry == null || isNull(entry.value())) {
        return agents;
      }
      Map<String, Entry> byName = mapping(entry.value(), "agents", null, Map.of());
      if (byName == null) {
        return agents;
      }
      byName.forEach(
          (name, agent) -> {
            String path = "agents." + name;
            if (!NAME.matcher(name).matches()) {
              error(
                  agent.key(),
                  path,
                  "`"
                      + name
                      + "` no vale como nombre de agente: usa minúsculas, números y guiones,"
                      + " empezando por una letra");
              return;
            }
            Map<String, Entry> fields = mapping(agent.value(), path, AGENT_KEYS, Map.of());
            if (fields == null) {
              return;
            }
            String prompt = text(fields.get("prompt"), path + ".prompt");
            List<String> tools = tools(fields.get("tools"), path + ".tools");
            String permissionMode = permissionMode(fields.get("permissionMode"), path);
            String model = model(fields.get("model"), path + ".model");
            String provider = provider(fields.get("provider"), path + ".provider");
            if (tools != null && AgentDefinition.CODEX.equals(provider)) {
              warning(
                  fields.get("tools").value(),
                  path + ".tools",
                  "Codex no admite una lista de herramientas: se ignora. Lo que puede hacer lo"
                      + " fija `permissionMode` (`plan` es solo lectura)");
            }
            Map<String, Object> output = output(fields.get("output"), path + ".output");
            Integer maxTurns = null;
            BigDecimal maxBudgetUsd = null;
            Integer timeoutMinutes = null;
            Entry limitsEntry = fields.get("limits");
            if (limitsEntry != null && !isNull(limitsEntry.value())) {
              Map<String, Entry> limits =
                  mapping(limitsEntry.value(), path + ".limits", LIMIT_KEYS, Map.of());
              if (limits != null) {
                maxTurns = integer(limits.get("maxTurns"), path + ".limits.maxTurns", 1, 1000);
                if (maxTurns != null && AgentDefinition.CODEX.equals(provider)) {
                  warning(
                      limits.get("maxTurns").value(),
                      path + ".limits.maxTurns",
                      "Codex no admite `maxTurns`: se ignora. Acota el agente con `maxBudgetUsd` o"
                          + " `timeoutMinutes`");
                  maxTurns = null;
                }
                maxBudgetUsd =
                    decimal(
                        limits.get("maxBudgetUsd"),
                        path + ".limits.maxBudgetUsd",
                        new BigDecimal("0.01"),
                        new BigDecimal("1000"));
                timeoutMinutes =
                    integer(limits.get("timeoutMinutes"), path + ".limits.timeoutMinutes", 1, 1440);
              }
            }
            agents.add(
                new AgentDefinition(
                    name,
                    text(fields.get("description"), path + ".description"),
                    prompt,
                    tools,
                    permissionMode,
                    maxTurns,
                    maxBudgetUsd,
                    timeoutMinutes,
                    model,
                    provider,
                    output));
          });
      return agents;
    }

    /**
     * El JSON Schema de la salida estructurada de un agente: un objeto con sus campos en {@code
     * properties}, cada uno con su {@code type}. Lo demás (descripciones, {@code required}...) pasa
     * tal cual a Claude Code, que valida la salida contra él.
     */
    private Map<String, Object> output(Entry entry, String path) {
      if (entry == null || isNull(entry.value())) {
        return null;
      }
      String example =
          ", p. ej. `output: {type: object, properties: {hasBlockingFindings: {type: boolean}}}`";
      if (!(entry.value() instanceof MappingNode mapping)) {
        error(entry.value(), path, "`output` debe ser un JSON Schema de un objeto" + example);
        return null;
      }
      Map<String, Node> schema = new LinkedHashMap<>();
      for (NodeTuple tuple : mapping.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key) {
          schema.put(key.getValue(), tuple.getValueNode());
        }
      }
      Node type = schema.get("type");
      if (!(type instanceof ScalarNode kind) || !kind.getValue().equals("object")) {
        error(
            type == null ? entry.key() : type,
            path + ".type",
            "La salida estructurada tiene que ser un objeto: pon `type: object` y sus campos en"
                + " `properties`");
        return null;
      }
      Node properties = schema.get("properties");
      if (!(properties instanceof MappingNode fields) || fields.getValue().isEmpty()) {
        error(
            properties == null ? entry.key() : properties,
            path + ".properties",
            "A `output` le faltan sus campos en `properties`" + example);
        return null;
      }
      boolean valid = true;
      for (NodeTuple field : fields.getValue()) {
        String name = field.getKeyNode() instanceof ScalarNode k ? k.getValue() : "";
        String fieldPath = path + ".properties." + name;
        if (!OUTPUT_FIELD.matcher(name).matches()) {
          error(
              field.getKeyNode(),
              fieldPath,
              "`"
                  + name
                  + "` no vale como campo de `output`: usa letras, números y `_`, empezando por"
                  + " una letra");
          valid = false;
          continue;
        }
        Node fieldType =
            field.getValueNode() instanceof MappingNode m
                ? m.getValue().stream()
                    .filter(
                        t -> t.getKeyNode() instanceof ScalarNode k && k.getValue().equals("type"))
                    .map(NodeTuple::getValueNode)
                    .findFirst()
                    .orElse(null)
                : null;
        if (!(fieldType instanceof ScalarNode ft) || !OUTPUT_TYPES.contains(ft.getValue())) {
          error(
              fieldType == null ? field.getKeyNode() : fieldType,
              fieldPath + ".type",
              "El campo `"
                  + name
                  + "` de `output` necesita `type`: "
                  + String.join(", ", OUTPUT_TYPES));
          valid = false;
        }
      }
      if (!valid) {
        return null;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> plain = (Map<String, Object>) plain(entry.value());
      return plain;
    }

    /** Un nodo del YAML como mapas, listas, textos, números, booleanos y {@code null}. */
    private static Object plain(Node node) {
      return switch (node) {
        case MappingNode mapping -> {
          Map<String, Object> map = new LinkedHashMap<>();
          for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key) {
              map.put(key.getValue(), plain(tuple.getValueNode()));
            }
          }
          yield map;
        }
        case SequenceNode sequence ->
            sequence.getValue().stream().map(DefinitionParser.Reader::plain).toList();
        case ScalarNode scalar when Tag.NULL.equals(scalar.getTag()) -> null;
        case ScalarNode scalar when Tag.BOOL.equals(scalar.getTag()) ->
            Boolean.parseBoolean(scalar.getValue());
        case ScalarNode scalar when Tag.INT.equals(scalar.getTag()) ->
            new java.math.BigInteger(scalar.getValue().replace("_", ""));
        case ScalarNode scalar when Tag.FLOAT.equals(scalar.getTag()) ->
            new BigDecimal(scalar.getValue().replace("_", ""));
        case ScalarNode scalar -> scalar.getValue();
        default -> null;
      };
    }

    private String model(Entry entry, String path) {
      String model = text(entry, path);
      if (model != null && !MODEL.matcher(model).matches()) {
        error(
            entry.value(),
            path,
            "`"
                + model
                + "` no vale como modelo: escribe su nombre o su alias, p. ej. `claude-sonnet-4-5`"
                + " o `opus`");
        return null;
      }
      return model;
    }

    private String provider(Entry entry, String path) {
      String provider = text(entry, path);
      if (provider == null || provider.equals(AgentDefinition.CLAUDE_CODE)) {
        return AgentDefinition.CLAUDE_CODE;
      }
      if (PROVIDERS.contains(provider)) {
        return provider;
      }
      if (FUTURE_PROVIDERS.contains(provider)) {
        add(
            Severity.UNSUPPORTED,
            path,
            entry.value(),
            "El proveedor `"
                + provider
                + "` todavía no se ejecuta: llega con el hito de proveedores. Por ahora solo `"
                + AgentDefinition.CLAUDE_CODE
                + "` y `"
                + AgentDefinition.CODEX
                + "`");
        return provider;
      }
      List<String> known = new ArrayList<>(List.of(AgentDefinition.CLAUDE_CODE));
      known.addAll(new java.util.TreeSet<>(PROVIDERS));
      known.addAll(new java.util.TreeSet<>(FUTURE_PROVIDERS));
      error(
          entry.value(),
          path,
          "Proveedor `"
              + provider
              + "` desconocido"
              + suggestion(provider, known)
              + ". Admite: "
              + String.join(", ", known));
      return null;
    }

    private List<String> tools(Entry entry, String path) {
      if (entry == null || isNull(entry.value())) {
        return null;
      }
      if (!(entry.value() instanceof SequenceNode sequence)) {
        error(entry.value(), path, "`tools` debe ser una lista, p. ej. `[Read, Grep, Edit]`");
        return null;
      }
      List<String> tools = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < sequence.getValue().size(); i++) {
        Node node = sequence.getValue().get(i);
        String tool = scalarText(node, path + "[" + i + "]");
        if (tool == null) {
          continue;
        }
        if (!TOOL.matcher(tool).matches()) {
          error(
              node,
              path + "[" + i + "]",
              "`"
                  + tool
                  + "` no parece una herramienta de Claude Code, como `Edit` o `Bash(git:*)`");
          continue;
        }
        if (!seen.add(tool)) {
          warning(node, path + "[" + i + "]", "`" + tool + "` está repetida");
          continue;
        }
        tools.add(tool);
      }
      return tools;
    }

    private String permissionMode(Entry entry, String path) {
      String mode = text(entry, path + ".permissionMode");
      if (mode != null && !AgentPolicy.PERMISSION_MODES.contains(mode)) {
        error(
            entry.value(),
            path + ".permissionMode",
            "Modo de permisos `"
                + mode
                + "` no admitido"
                + suggestion(mode, AgentPolicy.PERMISSION_MODES)
                + ". Admite: "
                + String.join(", ", AgentPolicy.PERMISSION_MODES));
        return null;
      }
      return mode;
    }

    // ---- Fases --------------------------------------------------------------------------------

    /** Una fase leída, con sus nodos para situar los errores que dependen de otras fases. */
    private record StageAt(
        StageDefinition stage,
        String path,
        Node node,
        Map<String, Entry> fields,
        List<Node> dependencyNodes,
        List<ChildAt> children) {}

    /** Un hijo leído de una fase {@code parallel}, con sus nodos. */
    private record ChildAt(ChildDefinition child, String path, Map<String, Entry> fields) {}

    private List<StageAt> stages(Entry entry) {
      if (!(entry.value() instanceof SequenceNode sequence)) {
        error(
            entry.value(),
            "stages",
            "`stages` debe ser una lista de fases, cada una empezando por `- id: ...`");
        return null;
      }
      if (sequence.getValue().isEmpty()) {
        error(entry.value(), "stages", "El workflow no tiene fases: añade al menos una");
        return null;
      }
      List<StageAt> stages = new ArrayList<>();
      for (int i = 0; i < sequence.getValue().size(); i++) {
        StageAt stage = stage(sequence.getValue().get(i), "stages[" + i + "]");
        if (stage != null) {
          stages.add(stage);
        }
      }
      return stages;
    }

    private StageAt stage(Node node, String path) {
      Map<String, Entry> fields = mapping(node, path, STAGE_KEYS, FUTURE_STAGE_KEYS);
      if (fields == null) {
        return null;
      }
      Entry idEntry = fields.get("id");
      if (idEntry == null) {
        error(node, path + ".id", "A esta fase le falta `id`");
        return null;
      }
      String id = text(idEntry, path + ".id");
      if (id == null) {
        return null;
      }
      if (!NAME.matcher(id).matches()) {
        error(
            idEntry.value(),
            path + ".id",
            "`"
                + id
                + "` no vale como id de fase: usa minúsculas, números y guiones, empezando por una"
                + " letra");
        return null;
      }
      path = "stages." + id;

      StageType type = StageType.AGENT;
      Entry typeEntry = fields.get("type");
      if (typeEntry == null) {
        error(node, path + ".type", "A la fase `" + id + "` le falta `type`, p. ej. `type: agent`");
      } else {
        String value = text(typeEntry, path + ".type");
        if (value != null) {
          StageType known = StageType.ofYaml(value).orElse(null);
          if (known == null) {
            List<String> names = Arrays.stream(StageType.values()).map(StageType::yaml).toList();
            error(
                typeEntry.value(),
                path + ".type",
                "Tipo de fase `"
                    + value
                    + "` desconocido"
                    + suggestion(value, names)
                    + ". Admite: "
                    + String.join(", ", names));
          } else {
            type = known;
            if (!known.executable()) {
              add(
                  Severity.UNSUPPORTED,
                  path + ".type",
                  typeEntry.value(),
                  "El tipo `"
                      + value
                      + "` todavía no se ejecuta"
                      + (known.milestone() == null ? "" : ": llega en " + known.milestone()));
            }
          }
        }
      }

      if (type.executable()) {
        for (Map.Entry<String, String> future : FUTURE_STAGE_KEYS.entrySet()) {
          Entry used = fields.get(future.getKey());
          if (used != null) {
            add(
                Severity.UNSUPPORTED,
                path + "." + future.getKey(),
                used.key(),
                "`" + future.getKey() + "` todavía no se usa: llega en " + future.getValue());
          }
        }
      }

      List<Dependency> dependsOn = new ArrayList<>();
      List<Node> dependencyNodes = new ArrayList<>();
      Entry depsEntry = fields.get("dependsOn");
      if (depsEntry != null && !isNull(depsEntry.value())) {
        if (depsEntry.value() instanceof SequenceNode deps) {
          Set<String> seen = new HashSet<>();
          for (int i = 0; i < deps.getValue().size(); i++) {
            Node dep = deps.getValue().get(i);
            String value = scalarText(dep, path + ".dependsOn[" + i + "]");
            if (value == null) {
              continue;
            }
            boolean optional = value.endsWith("?");
            String target = optional ? value.substring(0, value.length() - 1) : value;
            if (!seen.add(target)) {
              warning(dep, path + ".dependsOn[" + i + "]", "`" + target + "` está repetida");
              continue;
            }
            dependsOn.add(new Dependency(target, optional));
            dependencyNodes.add(dep);
          }
        } else {
          error(
              depsEntry.value(),
              path + ".dependsOn",
              "`dependsOn` debe ser una lista, p. ej. `dependsOn: [plan]`");
        }
      }

      WorkspaceMode workspace = WorkspaceMode.INHERIT;
      Entry workspaceEntry = fields.get("workspace");
      if (workspaceEntry != null) {
        String value = text(workspaceEntry, path + ".workspace");
        List<String> modes =
            Arrays.stream(WorkspaceMode.values()).map(WorkspaceMode::yaml).toList();
        if (value != null && !modes.contains(value)) {
          error(
              workspaceEntry.value(),
              path + ".workspace",
              "`workspace: "
                  + value
                  + "` no se conoce"
                  + suggestion(value, modes)
                  + ". Admite: "
                  + String.join(", ", modes));
        } else if (WorkspaceMode.ISOLATED.yaml().equals(value)) {
          workspace = WorkspaceMode.ISOLATED;
        }
      }

      String command = command(fields, type, node, path);
      List<ChildAt> children = children(fields, type, node, path);
      String condition = condition(fields, type, node, path);
      StageDefinition stage =
          new StageDefinition(
              id,
              text(fields.get("name"), path + ".name"),
              type,
              text(fields.get("agent"), path + ".agent"),
              text(fields.get("prompt"), path + ".prompt"),
              dependsOn,
              workspace,
              text(fields.get("workspaceFrom"), path + ".workspaceFrom"),
              command,
              children.stream().map(ChildAt::child).toList(),
              condition);
      return new StageAt(stage, path, node, fields, dependencyNodes, children);
    }

    /**
     * El texto de {@code condition}, que se comprueba con las demás fases ({@link
     * #checkCondition}). Una fase {@code conditional} no hace otra cosa: necesita su condición y no
     * lleva agente ni prompt.
     */
    private String condition(Map<String, Entry> fields, StageType type, Node node, String path) {
      String condition = text(fields.get("condition"), path + ".condition");
      if (type != StageType.CONDITIONAL) {
        return condition;
      }
      for (String key : List.of("agent", "prompt")) {
        Entry used = fields.get(key);
        if (used != null) {
          error(
              used.key(),
              path + "." + key,
              "Una fase `conditional` no hace trabajo, solo evalúa su condición: quita `"
                  + key
                  + "`");
        }
      }
      if (fields.get("condition") == null) {
        error(
            node,
            path + ".condition",
            "A esta fase le falta `condition`, p. ej. `condition: review.hasBlockingFindings`");
      }
      return condition;
    }

    /**
     * Los hijos de una fase {@code parallel}: obligatorios, cada uno con su agente, y sin agente,
     * prompt ni comando propios en la fase. En las demás fases, {@code children} no vale.
     */
    private List<ChildAt> children(
        Map<String, Entry> fields, StageType type, Node node, String path) {
      Entry entry = fields.get("children");
      if (type != StageType.PARALLEL) {
        if (entry != null && type.executable()) {
          error(
              entry.key(),
              path + ".children",
              "`children` solo vale en fases `type: parallel`; esta es `" + type.yaml() + "`");
        }
        return List.of();
      }
      for (String key : List.of("agent", "prompt")) {
        Entry used = fields.get(key);
        if (used != null) {
          error(
              used.key(),
              path + "." + key,
              "Una fase `parallel` no tiene agente propio: pon `"
                  + key
                  + "` en cada hijo de `children`");
        }
      }
      if (entry == null || isNull(entry.value())) {
        error(
            node,
            path + ".children",
            "A esta fase le falta `children`, los agentes que se ejecutan a la vez, p. ej."
                + " `children: [{agent: calidad}, {agent: seguridad}]`");
        return List.of();
      }
      if (!(entry.value() instanceof SequenceNode sequence) || sequence.getValue().isEmpty()) {
        error(
            entry.value(),
            path + ".children",
            "`children` debe ser una lista de hijos, cada uno empezando por `- agent: ...`");
        return List.of();
      }
      List<ChildAt> children = new ArrayList<>();
      Map<String, ChildAt> byId = new HashMap<>();
      for (int i = 0; i < sequence.getValue().size(); i++) {
        Node childNode = sequence.getValue().get(i);
        String childPath = path + ".children[" + i + "]";
        Map<String, Entry> childFields = mapping(childNode, childPath, CHILD_KEYS, Map.of());
        if (childFields == null) {
          continue;
        }
        Entry agentEntry = childFields.get("agent");
        if (agentEntry == null) {
          error(
              childNode,
              childPath + ".agent",
              "A este hijo le falta `agent`, el agente de `agents` que lo ejecuta");
          continue;
        }
        String agent = text(agentEntry, childPath + ".agent");
        if (agent == null) {
          continue;
        }
        Entry idEntry = childFields.get("id");
        String id = idEntry == null ? agent : text(idEntry, childPath + ".id");
        if (id == null) {
          continue;
        }
        if (!NAME.matcher(id).matches()) {
          error(
              idEntry == null ? agentEntry.value() : idEntry.value(),
              childPath + ".id",
              "`"
                  + id
                  + "` no vale como id de hijo: usa minúsculas, números y guiones, empezando por"
                  + " una letra");
          continue;
        }
        ChildAt child =
            new ChildAt(
                new ChildDefinition(
                    id, agent, text(childFields.get("prompt"), childPath + ".prompt")),
                childPath,
                childFields);
        ChildAt previous = byId.putIfAbsent(id, child);
        if (previous != null) {
          error(
              idEntry == null ? childNode : idEntry.value(),
              childPath + ".id",
              "Ya hay otro hijo `"
                  + id
                  + "` en esta fase: ponle a uno un `id` distinto, p. ej. `id: "
                  + id
                  + "-2`");
          continue;
        }
        children.add(child);
      }
      return children;
    }

    /**
     * El comando de una fase {@code command}: obligatorio, sin agente ni prompt, y sin variables
     * (un dato de entrada metido en un comando de shell podría ejecutar otra cosa). En las demás
     * fases, {@code command} no vale.
     */
    private String command(Map<String, Entry> fields, StageType type, Node node, String path) {
      Entry entry = fields.get("command");
      if (type != StageType.COMMAND) {
        if (entry != null && type.executable()) {
          error(
              entry.key(),
              path + ".command",
              "`command` solo vale en fases `type: command`; esta es `" + type.yaml() + "`");
        }
        return null;
      }
      for (String key : List.of("agent", "prompt")) {
        Entry used = fields.get(key);
        if (used != null) {
          error(
              used.key(),
              path + "." + key,
              "Una fase `command` ejecuta un comando, no un agente: quita `" + key + "`");
        }
      }
      if (entry == null || isNull(entry.value())) {
        error(
            node,
            path + ".command",
            "A esta fase le falta `command`, el comando que se ejecuta en el worktree, p. ej."
                + " `command: ./gradlew test`");
        return null;
      }
      String command = text(entry, path + ".command");
      if (command == null) {
        return null;
      }
      if (command.isBlank()) {
        error(entry.value(), path + ".command", "El comando está vacío");
        return null;
      }
      if (VARIABLE.matcher(command).find()) {
        error(
            entry.value(),
            path + ".command",
            "Los comandos no admiten variables `{{...}}`: un dato de entrada podría colar otro"
                + " comando. Escríbelo tal cual");
      }
      return command;
    }

    /** Lo que relaciona las fases entre sí y con los agentes y los datos. */
    private void checkStages(
        List<StageAt> stages, List<InputDefinition> inputs, List<AgentDefinition> agents) {
      Map<String, StageAt> byId = new LinkedHashMap<>();
      for (StageAt stage : stages) {
        StageAt previous = byId.putIfAbsent(stage.stage().id(), stage);
        if (previous != null) {
          error(
              stage.fields().get("id").value(),
              stage.path() + ".id",
              "Ya hay otra fase con id `"
                  + stage.stage().id()
                  + "` (línea "
                  + line(previous.node())
                  + ")");
        }
      }
      Map<String, AgentDefinition> agentsByName = new HashMap<>();
      agents.forEach(a -> agentsByName.put(a.name(), a));
      Set<String> inputNames =
          inputs.stream().map(InputDefinition::name).collect(Collectors.toSet());
      Set<String> usedAgents = new HashSet<>();

      for (StageAt at : stages) {
        StageDefinition stage = at.stage();
        for (int i = 0; i < stage.dependsOn().size(); i++) {
          Dependency dep = stage.dependsOn().get(i);
          Node node = at.dependencyNodes().get(i);
          String path = at.path() + ".dependsOn[" + i + "]";
          if (dep.stage().equals(stage.id())) {
            error(node, path, "La fase `" + stage.id() + "` no puede depender de sí misma");
          } else if (!byId.containsKey(dep.stage())) {
            error(
                node,
                path,
                "No hay ninguna fase `"
                    + dep.stage()
                    + "`"
                    + suggestion(dep.stage(), byId.keySet()));
          }
        }
        if (stage.type() == StageType.COMMAND) {
          checkWorkspace(at, byId);
          continue;
        }
        if (stage.type() == StageType.PARALLEL) {
          for (ChildAt child : at.children()) {
            String name = child.child().agent();
            usedAgents.add(name);
            AgentDefinition agent = agentsByName.get(name);
            if (agent == null) {
              error(
                  child.fields().get("agent").value(),
                  child.path() + ".agent",
                  "No hay ningún agente `"
                      + name
                      + "` en `agents`"
                      + suggestion(name, agentsByName.keySet()));
            } else if (child.child().prompt() == null && agent.prompt() == null) {
              error(
                  child.fields().get("agent").value(),
                  child.path() + ".prompt",
                  "El hijo `"
                      + child.child().id()
                      + "` no tiene prompt: escríbelo en el hijo o en el agente `"
                      + name
                      + "`");
            }
            if (child.child().prompt() != null) {
              checkVariables(
                  child.fields().get("prompt").value(), child.path() + ".prompt", inputNames);
            }
          }
          checkWorkspace(at, byId);
          continue;
        }
        if (stage.type() != StageType.AGENT) {
          continue;
        }
        AgentDefinition agent = null;
        if (stage.agent() != null) {
          usedAgents.add(stage.agent());
          agent = agentsByName.get(stage.agent());
          if (agent == null) {
            error(
                at.fields().get("agent").value(),
                at.path() + ".agent",
                "No hay ningún agente `"
                    + stage.agent()
                    + "` en `agents`"
                    + suggestion(stage.agent(), agentsByName.keySet()));
          }
        }
        if (stage.prompt() != null) {
          checkVariables(at.fields().get("prompt").value(), at.path() + ".prompt", inputNames);
        } else if (agent != null && agent.prompt() == null) {
          error(
              at.node(),
              at.path() + ".prompt",
              "La fase `"
                  + stage.id()
                  + "` no tiene prompt: escríbelo en la fase o en el agente `"
                  + agent.name()
                  + "`");
        } else if (agent == null && stage.agent() == null) {
          error(
              at.node(),
              at.path() + ".prompt",
              "La fase `" + stage.id() + "` necesita un `prompt` o un `agent` que lo tenga");
        }
        checkWorkspace(at, byId);
      }

      for (AgentDefinition agent : agents) {
        if (agent.prompt() != null) {
          Node prompt = promptNode(agent.name());
          if (prompt != null) {
            checkVariables(prompt, "agents." + agent.name() + ".prompt", inputNames);
          }
        }
        if (!usedAgents.contains(agent.name())) {
          warning(
              agentNode(agent.name()),
              "agents." + agent.name(),
              "Ninguna fase usa el agente `" + agent.name() + "`");
        }
      }
      checkCycles(stages, byId);
      for (StageAt at : stages) {
        if (at.stage().condition() != null && at.stage().type().executable()) {
          checkCondition(at, byId, agentsByName, inputs);
        }
      }
    }

    /**
     * Comprueba la condición de una fase: que se entienda, que solo lea datos de las fases de las
     * que depende (directa o indirectamente) y de {@code inputs}, y que sus tipos casen. El error
     * va en la línea y la columna del trozo que falla.
     */
    private void checkCondition(
        StageAt at,
        Map<String, StageAt> byId,
        Map<String, AgentDefinition> agents,
        List<InputDefinition> inputs) {
      Node node = at.fields().get("condition").value();
      String path = at.path() + ".condition";
      if (!(node instanceof ScalarNode scalar)) {
        return;
      }
      Set<String> ancestors = ancestors(at.stage().id(), byId);
      try {
        Condition.Expr expr = Condition.parse(scalar.getValue());
        Condition.check(
            expr, p -> conditionType(p, at.stage().id(), ancestors, byId, agents, inputs));
      } catch (Condition.SyntaxError e) {
        int[] position = position(scalar, Math.min(e.position(), scalar.getValue().length()));
        add(Severity.ERROR, path, position[0], position[1], e.getMessage());
      }
    }

    /** Fases de las que {@code stage} depende, directa o indirectamente. */
    private static Set<String> ancestors(String stage, Map<String, StageAt> byId) {
      Set<String> seen = new LinkedHashSet<>();
      List<String> pending = new ArrayList<>(List.of(stage));
      while (!pending.isEmpty()) {
        StageAt at = byId.get(pending.removeLast());
        if (at == null) {
          continue;
        }
        for (Dependency dep : at.stage().dependsOn()) {
          if (seen.add(dep.stage())) {
            pending.add(dep.stage());
          }
        }
      }
      seen.remove(stage);
      return seen;
    }

    /** El tipo del dato al que lleva una ruta de una condición, o un error si no vale. */
    private static Condition.Type conditionType(
        Condition.Path path,
        String stageId,
        Set<String> ancestors,
        Map<String, StageAt> byId,
        Map<String, AgentDefinition> agents,
        List<InputDefinition> inputs) {
      List<String> parts = path.parts();
      String first = parts.getFirst();
      String usage =
          "`"
              + path.text()
              + "` no es un dato que pueda leer una condición: usa `<fase>.status`,"
              + " `<fase>.exitCode`, `<fase>.<campo>` o `inputs.<nombre>`";
      if (first.equals("inputs")) {
        if (parts.size() != 2) {
          throw new Condition.SyntaxError(usage, path.position());
        }
        InputDefinition input =
            inputs.stream().filter(i -> i.name().equals(parts.get(1))).findFirst().orElse(null);
        if (input == null) {
          throw new Condition.SyntaxError(
              "`"
                  + path.text()
                  + "` usa un dato que no está en `inputs`"
                  + suggestion(parts.get(1), inputs.stream().map(InputDefinition::name).toList()),
              path.position());
        }
        return switch (input.type()) {
          case "number" -> Condition.Type.NUMBER;
          case "boolean" -> Condition.Type.BOOLEAN;
          default -> Condition.Type.STRING;
        };
      }
      StageAt source = byId.get(first);
      if (source == null) {
        throw new Condition.SyntaxError(
            "`" + first + "` no es ninguna fase ni `inputs`" + suggestion(first, byId.keySet()),
            path.position());
      }
      if (!ancestors.contains(first)) {
        throw new Condition.SyntaxError(
            "`"
                + first
                + "` no es una dependencia de `"
                + stageId
                + "`: una condición solo lee fases que han terminado antes (ponla en `dependsOn`)",
            path.position());
      }
      if (parts.size() < 2) {
        throw new Condition.SyntaxError(usage, path.position());
      }
      String field = parts.get(1);
      StageDefinition stage = source.stage();
      if (parts.size() == 2 && field.equals("status")) {
        return Condition.Type.STRING;
      }
      if (parts.size() == 2 && field.equals("exitCode")) {
        if (stage.type() != StageType.COMMAND) {
          throw new Condition.SyntaxError(
              "Solo las fases `command` tienen `exitCode`; `"
                  + first
                  + "` es `"
                  + stage.type().yaml()
                  + "`",
              path.position());
        }
        return Condition.Type.NUMBER;
      }
      if (stage.type() == StageType.PARALLEL) {
        if (parts.size() == 3) {
          ChildDefinition child = stage.child(field).orElse(null);
          if (child == null) {
            throw new Condition.SyntaxError(
                "La fase `"
                    + first
                    + "` no tiene ningún hijo `"
                    + field
                    + "`"
                    + suggestion(
                        field, stage.children().stream().map(ChildDefinition::id).toList()),
                path.position());
          }
          return outputType(path, agents.get(child.agent()), parts.get(2), first + "." + field);
        }
        if (parts.size() == 2) {
          boolean declared = false;
          for (ChildDefinition child : stage.children()) {
            AgentDefinition agent = agents.get(child.agent());
            String type = agent == null ? null : agent.outputFields().get(field);
            if (type != null && !type.equals("boolean")) {
              throw new Condition.SyntaxError(
                  "`"
                      + path.text()
                      + "` junta los hijos y solo vale para campos booleanos (es verdadero si"
                      + " algún hijo devuelve true); `"
                      + field
                      + "` es "
                      + type
                      + " en `"
                      + child.id()
                      + "`: usa `"
                      + first
                      + "."
                      + child.id()
                      + "."
                      + field
                      + "`",
                  path.position());
            }
            declared |= type != null;
          }
          if (!declared) {
            throw new Condition.SyntaxError(
                "Ningún hijo de `"
                    + first
                    + "` devuelve `"
                    + field
                    + "`: declara el campo en el `output` de su agente",
                path.position());
          }
          return Condition.Type.BOOLEAN;
        }
        throw new Condition.SyntaxError(usage, path.position());
      }
      if (stage.type() == StageType.AGENT && parts.size() == 2) {
        AgentDefinition agent = stage.agent() == null ? null : agents.get(stage.agent());
        return outputType(path, agent, field, first);
      }
      throw new Condition.SyntaxError(usage, path.position());
    }

    /** El tipo del campo {@code field} del {@code output} de {@code agent}. */
    private static Condition.Type outputType(
        Condition.Path path, AgentDefinition agent, String field, String who) {
      if (agent == null || agent.output() == null) {
        throw new Condition.SyntaxError(
            "`"
                + who
                + "` no devuelve salida estructurada: declara `output` en su agente para leer `"
                + field
                + "`",
            path.position());
      }
      String type = agent.outputFields().get(field);
      if (type == null) {
        throw new Condition.SyntaxError(
            "El `output` del agente `"
                + agent.name()
                + "` no tiene el campo `"
                + field
                + "`"
                + suggestion(field, agent.outputFields().keySet()),
            path.position());
      }
      return switch (type) {
        case "boolean" -> Condition.Type.BOOLEAN;
        case "number", "integer" -> Condition.Type.NUMBER;
        case "string" -> Condition.Type.STRING;
        default -> Condition.Type.ANY;
      };
    }

    private void checkWorkspace(StageAt at, Map<String, StageAt> byId) {
      StageDefinition stage = at.stage();
      List<String> agentDeps =
          stage.dependsOn().stream()
              .map(Dependency::stage)
              .filter(d -> byId.containsKey(d) && byId.get(d).stage().type().hasWorkspace())
              .toList();
      if (stage.workspaceFrom() != null) {
        Node node = at.fields().get("workspaceFrom").value();
        String path = at.path() + ".workspaceFrom";
        int dot = stage.workspaceFrom().indexOf('.');
        String from = dot < 0 ? stage.workspaceFrom() : stage.workspaceFrom().substring(0, dot);
        String child = dot < 0 ? null : stage.workspaceFrom().substring(dot + 1);
        Dependency dep =
            stage.dependsOn().stream().filter(d -> d.stage().equals(from)).findFirst().orElse(null);
        StageDefinition source =
            dep == null || !byId.containsKey(from) ? null : byId.get(from).stage();
        if (stage.workspace() == WorkspaceMode.ISOLATED) {
          error(
              node,
              path,
              "`workspaceFrom` no tiene sentido con `workspace: isolated-worktree`: quita uno de"
                  + " los dos");
        } else if (dep == null) {
          error(
              node,
              path,
              "`workspaceFrom` tiene que ser una de las fases de `dependsOn`"
                  + (agentDeps.isEmpty() ? "" : ": " + String.join(", ", agentDeps)));
        } else if (!agentDeps.contains(dep.stage())) {
          error(node, path, "`" + dep.stage() + "` no es una fase con worktree");
        } else if (child != null && source.type() != StageType.PARALLEL) {
          error(
              node,
              path,
              "`"
                  + from
                  + "` no es una fase `parallel`: `<fase>.<hijo>` solo vale para continuar el"
                  + " worktree de un hijo");
        } else if (child != null && source.child(child).isEmpty()) {
          error(
              node,
              path,
              "La fase `"
                  + from
                  + "` no tiene ningún hijo `"
                  + child
                  + "`"
                  + suggestion(
                      child, source.children().stream().map(ChildDefinition::id).toList()));
        } else if (dep.optional()) {
          error(
              node,
              path,
              "`"
                  + dep.stage()
                  + "` es una dependencia opcional: si se omite no habría worktree que continuar");
        }
        return;
      }
      if (stage.workspace() == WorkspaceMode.INHERIT && agentDeps.size() > 1) {
        error(
            at.node(),
            at.path() + ".workspace",
            "La fase `"
                + stage.id()
                + "` depende de varias fases con worktree ("
                + String.join(", ", agentDeps)
                + "): indica con `workspaceFrom` cuál continúa, o pon `workspace: isolated-worktree`"
                + " para empezar un worktree nuevo");
      }
    }

    private void checkCycles(List<StageAt> stages, Map<String, StageAt> byId) {
      Map<String, Integer> state = new HashMap<>();
      Set<Set<String>> reported = new HashSet<>();
      for (StageAt stage : stages) {
        visit(stage.stage().id(), byId, state, new ArrayList<>(), reported);
      }
    }

    /** Recorrido en profundidad: 1 = en la pila, 2 = terminado. */
    private void visit(
        String id,
        Map<String, StageAt> byId,
        Map<String, Integer> state,
        List<String> stack,
        Set<Set<String>> reported) {
      Integer current = state.get(id);
      if (current != null && current == 2) {
        return;
      }
      if (current != null && current == 1) {
        List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(id), stack.size()));
        if (reported.add(new HashSet<>(cycle))) {
          cycle.add(id);
          StageAt first = byId.get(id);
          error(
              first.node(),
              first.path() + ".dependsOn",
              "Las dependencias forman un ciclo: " + String.join(" → ", cycle));
        }
        return;
      }
      StageAt stage = byId.get(id);
      if (stage == null) {
        return;
      }
      state.put(id, 1);
      stack.add(id);
      for (Dependency dep : stage.stage().dependsOn()) {
        if (!dep.stage().equals(id)) {
          visit(dep.stage(), byId, state, stack, reported);
        }
      }
      stack.remove(stack.size() - 1);
      state.put(id, 2);
    }

    private void checkVariables(Node node, String path, Set<String> inputs) {
      if (!(node instanceof ScalarNode scalar)) {
        return;
      }
      String value = scalar.getValue();
      Matcher matcher = VARIABLE.matcher(value);
      while (matcher.find()) {
        String variable = matcher.group(1).strip();
        int[] position = position(scalar, matcher.start());
        if (variable.startsWith("inputs.")) {
          String name = variable.substring("inputs.".length());
          if (!inputs.contains(name)) {
            add(
                Severity.ERROR,
                path,
                position[0],
                position[1],
                "`{{"
                    + variable
                    + "}}` usa un dato que no está en `inputs`"
                    + suggestion(name, inputs));
          }
        } else if (variable.startsWith("stages.")) {
          add(
              Severity.UNSUPPORTED,
              path,
              position[0],
              position[1],
              "`{{" + variable + "}}`: usar la salida de otra fase llega en W4");
        } else if (!CONTEXT_VARIABLES.contains(variable)) {
          add(
              Severity.ERROR,
              path,
              position[0],
              position[1],
              "Variable `{{"
                  + variable
                  + "}}` desconocida"
                  + suggestion(variable, CONTEXT_VARIABLES)
                  + ". Admite: "
                  + String.join(", ", CONTEXT_VARIABLES)
                  + " e inputs.<nombre>");
        }
      }
    }

    /**
     * Línea y columna de un carácter del valor. Exacto en los bloques {@code |} y en los valores de
     * una línea; en el resto, el inicio del valor.
     */
    private int[] position(ScalarNode scalar, int index) {
      Mark start = scalar.getStartMark();
      String value = scalar.getValue();
      DumperOptions.ScalarStyle style = scalar.getScalarStyle();
      if (style == DumperOptions.ScalarStyle.LITERAL) {
        int lineDelta = 0;
        int lineStart = 0;
        for (int i = 0; i < index; i++) {
          if (value.charAt(i) == '\n') {
            lineDelta++;
            lineStart = i + 1;
          }
        }
        int contentLine = start.getLine() + 1;
        return new int[] {
          contentLine + lineDelta + 1, blockIndent(contentLine) + index - lineStart + 1
        };
      }
      boolean singleLine =
          start.getLine() == scalar.getEndMark().getLine()
              && style != DumperOptions.ScalarStyle.FOLDED;
      if (singleLine) {
        int quote =
            style == DumperOptions.ScalarStyle.DOUBLE_QUOTED
                    || style == DumperOptions.ScalarStyle.SINGLE_QUOTED
                ? 1
                : 0;
        return new int[] {start.getLine() + 1, start.getColumn() + quote + index + 1};
      }
      return new int[] {start.getLine() + 1, start.getColumn() + 1};
    }

    /** Sangría del bloque que empieza en esa línea (desde 0): la de su primera línea con texto. */
    private int blockIndent(int fromLine) {
      String[] lines = source.split("\n", -1);
      for (int i = fromLine; i < lines.length; i++) {
        String line = lines[i];
        if (!line.isBlank()) {
          return line.length() - line.stripLeading().length();
        }
      }
      return 0;
    }

    // ---- Búsquedas para situar errores --------------------------------------------------------

    private Node agentsNode;

    private Node agentNode(String name) {
      Entry agent = agentEntry(name);
      return agent == null ? null : agent.key();
    }

    private Node promptNode(String name) {
      Entry agent = agentEntry(name);
      if (agent == null || !(agent.value() instanceof MappingNode mapping)) {
        return null;
      }
      for (NodeTuple tuple : mapping.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals("prompt")) {
          return tuple.getValueNode();
        }
      }
      return null;
    }

    private Entry agentEntry(String name) {
      if (!(agentsNode instanceof MappingNode mapping)) {
        return null;
      }
      for (NodeTuple tuple : mapping.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name)) {
          return new Entry(key, tuple.getValueNode());
        }
      }
      return null;
    }

    // ---- Lectura de nodos ---------------------------------------------------------------------

    /** Una clave del YAML con su valor. */
    private record Entry(Node key, Node value) {}

    /**
     * Las claves de un objeto, en orden. Con {@code known} distinto de {@code null}, una clave que
     * no está ni ahí ni en {@code future} es un error. Devuelve {@code null} si no es un objeto.
     */
    private Map<String, Entry> mapping(
        Node node, String path, Set<String> known, Map<String, String> future) {
      if (!(node instanceof MappingNode mapping)) {
        error(
            node,
            path,
            (path.isEmpty() ? "El documento" : "`" + path + "`")
                + " debe ser un objeto con claves (`clave: valor`)");
        return null;
      }
      if (path.equals("agents")) {
        agentsNode = node;
      }
      Map<String, Entry> fields = new LinkedHashMap<>();
      for (NodeTuple tuple : mapping.getValue()) {
        Node keyNode = tuple.getKeyNode();
        if (!(keyNode instanceof ScalarNode scalar)) {
          error(keyNode, path, "Las claves deben ser texto");
          continue;
        }
        String key = scalar.getValue();
        String fieldPath = path.isEmpty() ? key : path + "." + key;
        if (fields.containsKey(key)) {
          error(
              keyNode,
              fieldPath,
              "`"
                  + key
                  + "` está repetida (la otra en la línea "
                  + line(fields.get(key).key())
                  + ")");
          continue;
        }
        if (known != null && !known.contains(key) && !future.containsKey(key)) {
          Set<String> candidates = new LinkedHashSet<>(known);
          candidates.addAll(future.keySet());
          error(
              keyNode, fieldPath, "Clave `" + key + "` desconocida" + suggestion(key, candidates));
          continue;
        }
        fields.put(key, new Entry(keyNode, tuple.getValueNode()));
      }
      return fields;
    }

    private String text(Entry entry, String path) {
      return entry == null ? null : scalarText(entry.value(), path);
    }

    /** Texto no vacío; {@code null} (y un error) si es una lista, un objeto o está vacío. */
    private String scalarText(Node node, String path) {
      if (!(node instanceof ScalarNode scalar)) {
        error(node, path, "`" + path + "` debe ser un texto, no una lista ni un objeto");
        return null;
      }
      if (isNull(node) || scalar.getValue().isBlank()) {
        error(node, path, "`" + path + "` está vacío");
        return null;
      }
      return scalar.getValue();
    }

    private Integer integer(Entry entry, String path, int min, int max) {
      if (entry == null) {
        return null;
      }
      String value = scalarText(entry.value(), path);
      if (value == null) {
        return null;
      }
      try {
        int number = Integer.parseInt(value.strip());
        if (number < min || number > max) {
          error(entry.value(), path, "`" + path + "` debe estar entre " + min + " y " + max);
          return null;
        }
        return number;
      } catch (NumberFormatException e) {
        error(entry.value(), path, "`" + path + "` debe ser un número entero");
        return null;
      }
    }

    private BigDecimal decimal(Entry entry, String path, BigDecimal min, BigDecimal max) {
      if (entry == null) {
        return null;
      }
      String value = scalarText(entry.value(), path);
      if (value == null) {
        return null;
      }
      try {
        BigDecimal number = new BigDecimal(value.strip());
        if ((min != null && number.compareTo(min) < 0)
            || (max != null && number.compareTo(max) > 0)) {
          error(
              entry.value(),
              path,
              "`"
                  + path
                  + "` debe estar entre "
                  + min.toPlainString()
                  + " y "
                  + max.toPlainString());
          return null;
        }
        return number;
      } catch (NumberFormatException e) {
        error(entry.value(), path, "`" + path + "` debe ser un número");
        return null;
      }
    }

    /** {@link Boolean#TRUE}, {@link Boolean#FALSE} o {@code null} si falta o no es booleano. */
    private Object bool(Entry entry, String path) {
      if (entry == null) {
        return null;
      }
      Node node = entry.value();
      if (node instanceof ScalarNode scalar && Tag.BOOL.equals(node.getTag())) {
        return Boolean.parseBoolean(scalar.getValue().toLowerCase(java.util.Locale.ROOT));
      }
      error(node, path, "`" + path + "` debe ser `true` o `false`");
      return null;
    }

    private static boolean isNull(Node node) {
      return Tag.NULL.equals(node.getTag());
    }

    // ---- Problemas ----------------------------------------------------------------------------

    private void error(Node node, String path, String message) {
      add(Severity.ERROR, path, node, message);
    }

    private void warning(Node node, String path, String message) {
      add(Severity.WARNING, path, node, message);
    }

    private void add(Severity severity, String path, Node node, String message) {
      Mark mark = node == null ? null : node.getStartMark();
      add(
          severity,
          path,
          mark == null ? null : mark.getLine() + 1,
          mark == null ? null : mark.getColumn() + 1,
          message);
    }

    private void add(Severity severity, String path, Integer line, Integer column, String message) {
      problems.add(new Problem(severity, path, line, column, message));
    }

    private static int line(Node node) {
      return node.getStartMark().getLine() + 1;
    }

    private static String yamlProblem(MarkedYAMLException e) {
      String problem = e.getProblem() != null ? e.getProblem() : e.getContext();
      return problem == null ? "formato incorrecto" : problem;
    }
  }

  /**
   * «; ¿quisiste decir `x`?» si hay un candidato a dos cambios o menos (uno en palabras cortas), o
   * nada.
   */
  static String suggestion(String value, java.util.Collection<String> candidates) {
    String best = null;
    int bestDistance = value.length() <= 4 ? 2 : 3;
    for (String candidate : candidates) {
      int distance =
          distance(
              value.toLowerCase(java.util.Locale.ROOT),
              candidate.toLowerCase(java.util.Locale.ROOT));
      if (distance < bestDistance) {
        best = candidate;
        bestDistance = distance;
      }
    }
    return best == null ? "" : "; ¿quisiste decir `" + best + "`?";
  }

  /** Distancia de edición contando como un solo cambio intercambiar dos letras seguidas. */
  private static int distance(String a, String b) {
    int[][] d = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= b.length(); j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (i > 1
            && j > 1
            && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
        }
      }
    }
    return d[a.length()][b.length()];
  }
}
