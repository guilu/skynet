package dev.skynet.runner.provider.gemini;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.provider.AgentEnvironment;
import dev.skynet.runner.provider.AgentProvider;
import dev.skynet.runner.provider.CliVersion;
import dev.skynet.runner.provider.CostMeter;
import dev.skynet.runner.provider.EventParser;
import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.StructuredOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Construye la invocación de {@code gemini --output-format stream-json} (ver {@code
 * docs/gemini-stream-json.md}): un arranque con el id de sesión que fija el control plane ({@code
 * --session-id}) o una reanudación ({@code --resume <sesión>}), con el modo de aprobación que
 * corresponde al modo de permisos y el modelo. Gemini CLI no bifurca sesiones ni tiene salida
 * estructurada: el {@code output} del agente se pide en el prompt y lo valida el parser.
 *
 * <p>Gemini guarda las sesiones por directorio de proyecto: reanudar funciona porque la sesión
 * sigue en el mismo worktree.
 */
public class GeminiProvider implements AgentProvider {

  /** Variables propias de Gemini CLI que hereda el agente, además de las básicas. */
  static final Set<String> GEMINI_ENV =
      Set.of(
          "GEMINI_API_KEY",
          "GOOGLE_API_KEY",
          "GOOGLE_CLOUD_PROJECT",
          "GOOGLE_CLOUD_LOCATION",
          "GOOGLE_GENAI_USE_VERTEXAI",
          "GOOGLE_GENAI_USE_GCA",
          "GOOGLE_APPLICATION_CREDENTIALS",
          "GOOGLE_GEMINI_BASE_URL",
          "GEMINI_CLI_HOME");

  /**
   * Los worktrees son carpetas nuevas: sin confiar en ellas, Gemini fuerza el modo de aprobación
   * {@code default}, que sin terminal deniega todo lo que pediría confirmación.
   */
  static final Map<String, String> FIXED_ENV = Map.of("GEMINI_CLI_TRUST_WORKSPACE", "true");

  private final String executable;
  private final AgentEnvironment env;
  private final ModelPrices prices;

  /**
   * @param executable ejecutable de Gemini CLI ({@code gemini}, o fake-gemini en pruebas)
   * @param extraEnv variables adicionales que el agente puede heredar
   */
  public GeminiProvider(String executable, List<String> extraEnv, ModelPrices prices) {
    this.executable = executable;
    this.env = new AgentEnvironment(GEMINI_ENV, extraEnv);
    this.prices = prices;
  }

  @Override
  public AgentProviderKind kind() {
    return AgentProviderKind.GEMINI;
  }

  @Override
  public String executable() {
    return executable;
  }

  @Override
  public List<String> command(StartAgent start, Path files) throws IOException {
    List<String> command =
        new ArrayList<>(
            List.of(
                executable,
                "--output-format",
                "stream-json",
                "--approval-mode",
                approvalMode(start.permissionMode())));
    if (start.model() != null) {
      command.addAll(List.of("--model", start.model()));
    }
    ResumeFrom resume = start.resume();
    if (resume == null) {
      command.addAll(List.of("--session-id", start.sessionId().toString()));
    } else if (resume.fork()) {
      throw new IOException("Gemini CLI no permite bifurcar una sesión");
    } else {
      command.addAll(List.of("--resume", resume.sessionId()));
    }
    String prompt = start.prompt();
    if (start.outputSchema() != null) {
      prompt += new StructuredOutput(start.outputSchema()).instruction();
    }
    // Con "=": un prompt que empiece por "-" no se confunde con una opción.
    command.add("--prompt=" + prompt);
    return command;
  }

  /**
   * Modo de aprobación según el modo de permisos de la política: {@code plan} es solo lectura,
   * {@code bypassPermissions} lo aprueba todo y los demás aprueban las ediciones (sin terminal, lo
   * que pediría confirmación, como los comandos de shell, se deniega).
   */
  static String approvalMode(String permissionMode) {
    return switch (permissionMode) {
      case "plan" -> "plan";
      case "bypassPermissions" -> "yolo";
      case null, default -> "auto_edit";
    };
  }

  @Override
  public EventParser newParser(StartAgent start) {
    return new GeminiEventParser(
        start.model(),
        start.outputSchema() == null ? null : new StructuredOutput(start.outputSchema()),
        prices);
  }

  @Override
  public CostMeter newCostMeter(StartAgent start) {
    return new GeminiCostMeter(prices);
  }

  @Override
  public Map<String, String> environment(Map<String, String> runnerEnv, List<String> requested) {
    Map<String, String> out = new HashMap<>(env.of(runnerEnv, requested));
    out.putAll(FIXED_ENV);
    return out;
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
