package dev.skynet.runner.provider.codex;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.provider.AgentEnvironment;
import dev.skynet.runner.provider.AgentProvider;
import dev.skynet.runner.provider.CliVersion;
import dev.skynet.runner.provider.CostMeter;
import dev.skynet.runner.provider.EventParser;
import dev.skynet.runner.provider.ModelPrices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Construye la invocación de {@code codex exec --json} (ver {@code docs/codex-exec-json.md}): un
 * arranque, una reanudación ({@code exec resume <sesión>}) o un fork ({@code exec fork <sesión>}),
 * con el sandbox que corresponde al modo de permisos, el modelo y el schema de la salida.
 *
 * <p>Codex guarda sus sesiones en {@code $CODEX_HOME/sessions} sin separarlas por directorio, así
 * que bifurcar en otro worktree no necesita copiar nada. No admite límite de turnos ni de
 * presupuesto: el presupuesto lo hace cumplir el runner con su estimación.
 */
public class CodexProvider implements AgentProvider {

  /** Variables propias de Codex que hereda el agente, además de las básicas. */
  static final Set<String> CODEX_ENV =
      Set.of("CODEX_HOME", "OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL");

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final String executable;
  private final AgentEnvironment env;
  private final ModelPrices prices;

  /**
   * @param executable ejecutable de Codex ({@code codex}, o fake-codex en pruebas)
   * @param extraEnv variables adicionales que el agente puede heredar
   */
  public CodexProvider(String executable, List<String> extraEnv, ModelPrices prices) {
    this.executable = executable;
    this.env = new AgentEnvironment(CODEX_ENV, extraEnv);
    this.prices = prices;
  }

  @Override
  public AgentProviderKind kind() {
    return AgentProviderKind.CODEX;
  }

  @Override
  public String executable() {
    return executable;
  }

  @Override
  public List<String> command(StartAgent start, Path files) throws IOException {
    List<String> command = new ArrayList<>(List.of(executable, "exec", "--json"));
    command.addAll(sandbox(start.permissionMode()));
    if (start.model() != null) {
      command.addAll(List.of("--model", start.model()));
    }
    if (start.outputSchema() != null) {
      Path schema = Path.of(files + ".schema.json");
      Files.createDirectories(schema.getParent());
      Files.writeString(schema, strict(start.outputSchema()));
      command.addAll(List.of("--output-schema", schema.toString()));
    }
    ResumeFrom resume = start.resume();
    if (resume != null) {
      command.addAll(List.of(resume.fork() ? "fork" : "resume", resume.sessionId()));
    }
    // Tras "--": un prompt que empiece por "-" no se confunde con una opción.
    command.addAll(List.of("--", start.prompt()));
    return command;
  }

  /**
   * Sandbox según el modo de permisos de la política: {@code plan} es solo lectura, {@code
   * bypassPermissions} lo quita y los demás dejan escribir en el worktree.
   */
  static List<String> sandbox(String permissionMode) {
    return switch (permissionMode) {
      case "plan" -> List.of("--sandbox", "read-only");
      case "bypassPermissions" -> List.of("--dangerously-bypass-approvals-and-sandbox");
      case null, default -> List.of("--sandbox", "workspace-write");
    };
  }

  /**
   * El schema en el modo estricto de OpenAI: cada objeto con {@code additionalProperties: false} y
   * todas sus propiedades en {@code required}. Sin eso Codex rechaza el schema.
   */
  static String strict(String schema) {
    JsonNode node = JSON.readTree(schema);
    strict(node);
    return JSON.writeValueAsString(node);
  }

  private static void strict(JsonNode node) {
    if (node instanceof ObjectNode object) {
      JsonNode properties = object.get("properties");
      if ("object".equals(object.path("type").asString(null)) || properties != null) {
        object.put("additionalProperties", false);
        if (properties instanceof ObjectNode props) {
          ArrayNode required = object.putArray("required");
          props.propertyNames().forEach(required::add);
        }
      }
      object.values().forEach(CodexProvider::strict);
    } else if (node instanceof ArrayNode array) {
      array.forEach(CodexProvider::strict);
    }
  }

  @Override
  public EventParser newParser(StartAgent start) {
    return new CodexEventParser(start.model(), start.outputSchema() != null, prices);
  }

  @Override
  public CostMeter newCostMeter(StartAgent start) {
    return new CodexCostMeter(prices, start.model());
  }

  @Override
  public Map<String, String> environment(Map<String, String> runnerEnv, List<String> requested) {
    return env.of(runnerEnv, requested);
  }

  @Override
  public List<String> refusedEnvironment(StartAgent start) {
    return env.refused(start.environment());
  }

  @Override
  public Optional<String> version(Map<String, String> runnerEnv) {
    return CliVersion.of(executable, environment(runnerEnv, null));
  }
}
