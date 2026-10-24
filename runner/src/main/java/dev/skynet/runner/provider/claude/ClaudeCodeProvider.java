package dev.skynet.runner.provider.claude;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.memory.MemoryBridge;
import dev.skynet.runner.provider.AgentEnvironment;
import dev.skynet.runner.provider.AgentProvider;
import dev.skynet.runner.provider.CliVersion;
import dev.skynet.runner.provider.ModelPrices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Construye la invocación de {@code claude -p} para una orden de arranque o de reanudación (ver
 * {@code docs/claude-code-stream-json.md}): salida {@code stream-json} completa, id de sesión
 * fijado por el control plane o sesión que se reanuda, herramientas permitidas y límites.
 */
public class ClaudeCodeProvider implements AgentProvider {

  /** Variables propias de Claude Code que hereda el agente, además de las básicas. */
  static final Set<String> CLAUDE_ENV =
      Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "CLAUDE_CONFIG_DIR");

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final String executable;
  private final AgentEnvironment env;
  private final ModelPrices prices;
  private final ClaudeSessions sessions;

  /**
   * @param executable ejecutable de Claude Code ({@code claude}, o fake-claude en pruebas)
   * @param extraEnv variables adicionales que el agente puede heredar
   */
  public ClaudeCodeProvider(String executable, List<String> extraEnv) {
    this(executable, extraEnv, ModelPrices.defaults());
  }

  /**
   * @param prices precios con los que se estima el coste de cada invocación para hacer cumplir su
   *     presupuesto
   */
  public ClaudeCodeProvider(String executable, List<String> extraEnv, ModelPrices prices) {
    this(executable, extraEnv, prices, null);
  }

  /**
   * @param sessions dónde guarda Claude Code las sesiones, para copiarlas al bifurcar; {@code null}
   *     si no se sabe
   */
  public ClaudeCodeProvider(
      String executable, List<String> extraEnv, ModelPrices prices, ClaudeSessions sessions) {
    this.executable = executable;
    this.env = new AgentEnvironment(CLAUDE_ENV, extraEnv);
    this.prices = prices;
    this.sessions = sessions;
  }

  @Override
  public AgentProviderKind kind() {
    return AgentProviderKind.CLAUDE_CODE;
  }

  @Override
  public String executable() {
    return executable;
  }

  @Override
  public ClaudeStreamParser newParser(StartAgent start) {
    return new ClaudeStreamParser();
  }

  @Override
  public CostEstimator newCostMeter(StartAgent start) {
    return new CostEstimator(prices);
  }

  /** {@code --resume} solo busca en el directorio del cwd: la sesión se copia al worktree nuevo. */
  @Override
  public boolean copySession(String sessionId, Path from, Path to) throws IOException {
    return sessions != null && sessions.copy(sessionId, from, to);
  }

  /** Claude Code no necesita ficheros auxiliares: el schema va en la línea de comandos. */
  @Override
  public List<String> command(StartAgent start, Path files) {
    return command(start);
  }

  @Override
  public boolean supportsMemory() {
    return true;
  }

  /**
   * Con memoria, el servidor MCP va en {@code <files>.mcp.json} ({@code --mcp-config}) y sus
   * herramientas se permiten aunque la fase limite las demás.
   */
  @Override
  public List<String> command(StartAgent start, Path files, MemoryBridge memory)
      throws IOException {
    List<String> command = command(start);
    if (memory == null) {
      return command;
    }
    Path config = Path.of(files + ".mcp.json");
    Files.createDirectories(config.getParent());
    ObjectNode root = JSON.createObjectNode();
    ObjectNode server = root.putObject("mcpServers").putObject(MemoryBridge.SERVER);
    server.put("type", "stdio");
    server.put("command", memory.executable());
    memory.args().forEach(server.putArray("args")::add);
    Files.writeString(config, JSON.writeValueAsString(root));
    // Antes de "--" y el prompt, que van siempre al final.
    List<String> options = new ArrayList<>(List.of("--mcp-config", config.toString()));
    int allowed = command.indexOf("--allowedTools");
    if (allowed >= 0 && allowed < command.size() - 2) {
      command.set(allowed + 1, command.get(allowed + 1) + "," + MEMORY_TOOLS);
    } else {
      options.addAll(List.of("--allowedTools", MEMORY_TOOLS));
    }
    command.addAll(command.size() - 2, options);
    return command;
  }

  /** Las herramientas del servidor de memoria, para {@code --allowedTools}. */
  static final String MEMORY_TOOLS = "mcp__" + MemoryBridge.SERVER;

  public List<String> command(StartAgent start) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.add("-p");
    command.addAll(
        List.of("--output-format", "stream-json", "--verbose", "--include-partial-messages"));
    ResumeFrom resume = start.resume();
    if (resume == null) {
      command.addAll(List.of("--session-id", start.sessionId().toString()));
    } else if (resume.fork()) {
      // El CLI solo admite --session-id junto a --resume si se bifurca: fija el id de la rama
      // nueva.
      command.addAll(
          List.of(
              "--resume",
              resume.sessionId(),
              "--fork-session",
              "--session-id",
              start.sessionId().toString()));
    } else {
      command.addAll(List.of("--resume", resume.sessionId()));
    }
    if (start.permissionMode() != null) {
      command.addAll(List.of("--permission-mode", start.permissionMode()));
    }
    if (!start.allowedTools().isEmpty()) {
      command.addAll(List.of("--allowedTools", String.join(",", start.allowedTools())));
    }
    if (start.model() != null) {
      command.addAll(List.of("--model", start.model()));
    }
    if (start.outputSchema() != null) {
      // Claude Code valida la salida contra el schema y la devuelve en structured_output.
      command.addAll(List.of("--json-schema", start.outputSchema()));
    }
    AgentLimits limits = start.limits();
    if (limits.maxTurns() != null) {
      command.addAll(List.of("--max-turns", limits.maxTurns().toString()));
    }
    if (limits.maxBudgetUsd() != null) {
      // No es un límite estricto (se comprueba al final de cada turno), pero acota el gasto.
      command.addAll(List.of("--max-budget-usd", limits.maxBudgetUsd().toPlainString()));
    }
    // Al final y tras "--": un prompt que empiece por "-" no se confunde con una opción.
    command.addAll(List.of("--", start.prompt()));
    return command;
  }

  /** Versión del CLI instalado ({@code claude --version}), si se puede obtener. */
  @Override
  public Optional<String> version(Map<String, String> runnerEnv) {
    return CliVersion.of(executable, environment(runnerEnv));
  }

  /** Entorno del agente: solo las variables permitidas del runner. */
  public Map<String, String> environment(Map<String, String> runnerEnv) {
    return environment(runnerEnv, null);
  }

  @Override
  public Map<String, String> environment(Map<String, String> runnerEnv, List<String> requested) {
    return env.of(runnerEnv, requested);
  }

  @Override
  public List<String> refusedEnvironment(StartAgent start) {
    return env.refused(start.environment());
  }
}
