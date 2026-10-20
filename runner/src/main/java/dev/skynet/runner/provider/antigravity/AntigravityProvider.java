package dev.skynet.runner.provider.antigravity;

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

/**
 * Construye la invocación de {@code agy --output-format stream-json} (ver {@code
 * docs/antigravity-stream-json.md}): un arranque o una reanudación ({@code --conversation <id>}),
 * con el modo que corresponde al modo de permisos, el modelo y el schema de la salida ({@code
 * --json-schema}). agy no bifurca conversaciones: el id lo asigna él al arrancar y lo cuenta en
 * {@code init}.
 */
public class AntigravityProvider implements AgentProvider {

  private final String executable;
  private final AgentEnvironment env;
  private final ModelPrices prices;

  /**
   * @param executable ejecutable de agy ({@code agy}, o fake-agy en pruebas)
   * @param extraEnv variables adicionales que el agente puede heredar
   */
  public AntigravityProvider(String executable, List<String> extraEnv, ModelPrices prices) {
    this.executable = executable;
    // agy guarda su sesión de Google en el HOME del usuario: no necesita variables propias.
    this.env = new AgentEnvironment(Set.of(), extraEnv);
    this.prices = prices;
  }

  @Override
  public AgentProviderKind kind() {
    return AgentProviderKind.ANTIGRAVITY;
  }

  @Override
  public String executable() {
    return executable;
  }

  @Override
  public List<String> command(StartAgent start, Path files) throws IOException {
    List<String> command = new ArrayList<>(List.of(executable, "--output-format", "stream-json"));
    command.addAll(mode(start.permissionMode()));
    if (start.model() != null) {
      command.addAll(List.of("--model", start.model()));
    }
    if (start.outputSchema() != null) {
      Path schema = Path.of(files + ".schema.json");
      Files.createDirectories(schema.getParent());
      Files.writeString(schema, start.outputSchema());
      command.addAll(List.of("--json-schema", schema.toString()));
    }
    ResumeFrom resume = start.resume();
    if (resume != null) {
      if (resume.fork()) {
        throw new IOException("Antigravity CLI no permite bifurcar una conversación");
      }
      command.addAll(List.of("--conversation", resume.sessionId()));
    }
    // Con "=": un prompt que empiece por "-" no se confunde con una opción.
    command.add("--print=" + start.prompt());
    return command;
  }

  /**
   * Modo según el modo de permisos de la política. Sin terminal agy no puede pedir permiso, así que
   * lo que lo pediría se deniega solo:
   *
   * <ul>
   *   <li>{@code plan}: no edita ni ejecuta comandos (modo restrictivo);
   *   <li>{@code dontAsk} y {@code bypassPermissions}: lo aprueba todo, pero siempre dentro del
   *       sandbox de agy, para que un revisor pueda leer el código con comandos ({@code find},
   *       {@code ls}) sin tocar nada fuera del worktree. Nunca se genera bypass sin sandbox;
   *   <li>los demás aprueban las ediciones y deniegan los comandos.
   * </ul>
   */
  static List<String> mode(String permissionMode) {
    return switch (permissionMode) {
      case "plan" -> List.of("--mode", "plan");
      case "dontAsk", "bypassPermissions" -> List.of("--dangerously-skip-permissions", "--sandbox");
      case null, default -> List.of("--mode", "accept-edits");
    };
  }

  @Override
  public EventParser newParser(StartAgent start) {
    return new AntigravityEventParser(start.model(), start.outputSchema() != null, prices);
  }

  @Override
  public CostMeter newCostMeter(StartAgent start) {
    return new AntigravityCostMeter(prices, start.model());
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
