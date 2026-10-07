package dev.skynet.runner.provider.claude;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Construye la invocación de {@code claude -p} para una orden de arranque o de reanudación (ver
 * {@code docs/claude-code-stream-json.md}): salida {@code stream-json} completa, id de sesión
 * fijado por el control plane o sesión que se reanuda, herramientas permitidas y límites.
 */
public class ClaudeCodeProvider {

  /**
   * Variables de entorno que el agente hereda del runner. El resto se filtra para no filtrar
   * secretos del runner al agente.
   */
  static final Set<String> INHERITED_ENV =
      Set.of(
          "PATH",
          "HOME",
          "USER",
          "LOGNAME",
          "SHELL",
          "LANG",
          "LC_ALL",
          "LC_CTYPE",
          "TERM",
          "TMPDIR",
          "TZ",
          "ANTHROPIC_API_KEY",
          "ANTHROPIC_BASE_URL",
          "CLAUDE_CONFIG_DIR",
          "HTTP_PROXY",
          "HTTPS_PROXY",
          "NO_PROXY",
          "http_proxy",
          "https_proxy",
          "no_proxy");

  private final String executable;
  private final List<String> extraEnv;
  private final ModelPrices prices;

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
    this.executable = executable;
    this.extraEnv = List.copyOf(extraEnv);
    this.prices = prices;
  }

  /** Estimador de coste para una invocación nueva. */
  public CostEstimator costEstimator() {
    return new CostEstimator(prices);
  }

  public List<String> command(StartAgent start) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.add("-p");
    command.add(start.prompt());
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
    AgentLimits limits = start.limits();
    if (limits.maxTurns() != null) {
      command.addAll(List.of("--max-turns", limits.maxTurns().toString()));
    }
    if (limits.maxBudgetUsd() != null) {
      // No es un límite estricto (se comprueba al final de cada turno), pero acota el gasto.
      command.addAll(List.of("--max-budget-usd", limits.maxBudgetUsd().toPlainString()));
    }
    return command;
  }

  /** Versión del CLI instalado ({@code claude --version}), si se puede obtener. */
  public java.util.Optional<String> version(Map<String, String> runnerEnv) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(executable, "--version").redirectErrorStream(true);
      builder.environment().clear();
      builder.environment().putAll(environment(runnerEnv));
      Process process = builder.start();
      process.getOutputStream().close();
      String output = new String(process.getInputStream().readAllBytes()).trim();
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(output.lines().findFirst().orElse(output));
    } catch (java.io.IOException e) {
      return java.util.Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return java.util.Optional.empty();
    }
  }

  /** Entorno del agente: solo las variables permitidas del runner. */
  public Map<String, String> environment(Map<String, String> runnerEnv) {
    return environment(runnerEnv, null);
  }

  /**
   * Entorno de una invocación: las variables básicas y, de las adicionales que permite el runner,
   * solo las que pide la política del repositorio ({@code null}: todas). Las que pide y el runner
   * no permite no se pasan.
   */
  public Map<String, String> environment(Map<String, String> runnerEnv, List<String> requested) {
    Map<String, String> env = new LinkedHashMap<>();
    runnerEnv.forEach(
        (name, value) -> {
          if (INHERITED_ENV.contains(name)
              || (extraEnv.contains(name) && (requested == null || requested.contains(name)))) {
            env.put(name, value);
          }
        });
    return env;
  }

  /** Variables que pide la invocación y el runner no permite: no las recibe el agente. */
  public List<String> refusedEnvironment(StartAgent start) {
    return start.environment() == null
        ? List.of()
        : start.environment().stream()
            .filter(name -> !INHERITED_ENV.contains(name) && !extraEnv.contains(name))
            .toList();
  }
}
