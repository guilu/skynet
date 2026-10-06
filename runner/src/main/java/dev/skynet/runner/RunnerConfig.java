package dev.skynet.runner;

import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Configuración del runner, leída de variables de entorno.
 *
 * @param controlPlane URL base del control plane ({@code SKYNET_URL})
 * @param name nombre con el que se registra ({@code SKYNET_RUNNER_NAME}, por defecto el host)
 * @param registrationToken secreto de registro ({@code SKYNET_RUNNER_REGISTRATION_TOKEN}); solo
 *     hace falta la primera vez, después se usa el token guardado en el journal
 * @param capacity agentes simultáneos ({@code SKYNET_RUNNER_CAPACITY})
 * @param home directorio de datos: journal, logs y worktrees ({@code SKYNET_RUNNER_HOME})
 * @param claudeExecutable ejecutable de Claude Code ({@code SKYNET_CLAUDE_BIN})
 * @param extraEnv variables de entorno adicionales que se pasan al agente ({@code
 *     SKYNET_AGENT_ENV}, separadas por comas)
 */
public record RunnerConfig(
    URI controlPlane,
    String name,
    String registrationToken,
    int capacity,
    Path home,
    String claudeExecutable,
    List<String> extraEnv,
    Duration pollWait,
    Duration heartbeatInterval,
    Duration cancelGrace) {

  public Path journalFile() {
    return home.resolve("journal.db");
  }

  public Path workspacesDir() {
    return home.resolve("workspaces");
  }

  public Path logsDir() {
    return home.resolve("logs");
  }

  /** Artefactos pendientes de subir. */
  public Path artifactsDir() {
    return home.resolve("artifacts");
  }

  public static RunnerConfig fromEnvironment(Map<String, String> env) {
    return new RunnerConfig(
        URI.create(env.getOrDefault("SKYNET_URL", "http://localhost:8080")),
        env.getOrDefault("SKYNET_RUNNER_NAME", hostname()),
        env.get("SKYNET_RUNNER_REGISTRATION_TOKEN"),
        Integer.parseInt(env.getOrDefault("SKYNET_RUNNER_CAPACITY", "2")),
        Path.of(
            env.getOrDefault(
                "SKYNET_RUNNER_HOME",
                Path.of(System.getProperty("user.home"), ".skynet-runner").toString())),
        env.getOrDefault("SKYNET_CLAUDE_BIN", "claude"),
        Arrays.stream(env.getOrDefault("SKYNET_AGENT_ENV", "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList(),
        Duration.ofSeconds(25),
        Duration.ofSeconds(15),
        Duration.ofSeconds(10));
  }

  private static String hostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "runner";
    }
  }
}
