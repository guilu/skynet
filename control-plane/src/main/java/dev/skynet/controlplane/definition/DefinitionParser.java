package dev.skynet.controlplane.definition;

import dev.skynet.controlplane.definition.Problem.Severity;
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
      Set.of("description", "prompt", "tools", "permissionMode", "limits");
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
          "workspaceFrom");

  /** Claves de §11 que llegan con hitos posteriores, con el hito que las ejecuta. */
  static final Map<String, String> FUTURE_STAGE_KEYS =
      Map.of(
          "promptTemplate", "S3",
          "outputSchema", "W4",
          "artifacts", "W4",
          "retry", "W6",
          "timeout", "W6",
          "condition", "W3",
          "children", "W3",
          "command", "W3");

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

  static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,48}");
  static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
  static final Pattern TOOL = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\(.+\\))?");
  private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(.*?)\\s*}}");

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
            Boolean required = bool(fields.get("required"), path + ".required");
            Object defaultValue = defaultValue(fields.get("default"), path + ".default", type);
            inputs.add(
                new InputDefinition(
                    name,
                    type,
                    required != null && required,
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
            Integer maxTurns = null;
            BigDecimal maxBudgetUsd = null;
            Integer timeoutMinutes = null;
            Entry limitsEntry = fields.get("limits");
            if (limitsEntry != null && !isNull(limitsEntry.value())) {
              Map<String, Entry> limits =
                  mapping(limitsEntry.value(), path + ".limits", LIMIT_KEYS, Map.of());
              if (limits != null) {
                maxTurns = integer(limits.get("maxTurns"), path + ".limits.maxTurns", 1, 1000);
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
                    timeoutMinutes));
          });
      return agents;
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
        List<Node> dependencyNodes) {}

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

      if (type == StageType.AGENT) {
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

      StageDefinition stage =
          new StageDefinition(
              id,
              text(fields.get("name"), path + ".name"),
              type,
              text(fields.get("agent"), path + ".agent"),
              text(fields.get("prompt"), path + ".prompt"),
              dependsOn,
              workspace,
              text(fields.get("workspaceFrom"), path + ".workspaceFrom"));
      return new StageAt(stage, path, node, fields, dependencyNodes);
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
    }

    private void checkWorkspace(StageAt at, Map<String, StageAt> byId) {
      StageDefinition stage = at.stage();
      List<String> agentDeps =
          stage.dependsOn().stream()
              .map(Dependency::stage)
              .filter(d -> byId.containsKey(d) && byId.get(d).stage().type() == StageType.AGENT)
              .toList();
      if (stage.workspaceFrom() != null) {
        Node node = at.fields().get("workspaceFrom").value();
        String path = at.path() + ".workspaceFrom";
        Dependency dep =
            stage.dependsOn().stream()
                .filter(d -> d.stage().equals(stage.workspaceFrom()))
                .findFirst()
                .orElse(null);
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
          error(node, path, "`" + dep.stage() + "` no es una fase con agente: no tiene worktree");
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
                + "` depende de varias fases con agente ("
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
        String variable = matcher.group(1);
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

    private Boolean bool(Entry entry, String path) {
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
