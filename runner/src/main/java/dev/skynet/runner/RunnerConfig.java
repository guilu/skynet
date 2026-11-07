package dev.skynet.runner;

import dev.skynet.runner.verify.DeclaredFiles;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * @param codexExecutable ejecutable de Codex ({@code SKYNET_CODEX_BIN})
 * @param extraEnv variables de entorno adicionales que se pasan al agente ({@code
 *     SKYNET_AGENT_ENV}, separadas por comas)
 * @param modelPrices fichero de precios por modelo que amplía o corrige la tabla con la que se
 *     estima el coste ({@code SKYNET_MODEL_PRICES}), o {@code null}
 * @param logRetention cuánto se conservan los logs locales ya subidos ({@code
 *     SKYNET_LOG_RETENTION_DAYS}, 7 días por defecto)
 * @param providers proveedores que declara el runner ({@code SKYNET_PROVIDERS}, separados por
 *     comas), o {@code null} para los que respondan a {@code --version}
 * @param agyExecutable ejecutable de Antigravity CLI ({@code SKYNET_AGY_BIN})
 * @param quotaPoll cada cuánto pregunta su cuota a Codex y Antigravity ({@code SKYNET_QUOTA_POLL}:
 *     {@code 10m} por defecto, {@code 30s}, {@code 1h}…; {@code off} lo apaga), o {@code null}
 * @param declaredFiles límites de los ficheros declarados en {@code artifacts} que se suben de una
 *     fase ({@code SKYNET_ARTIFACT_FILE_MAX_MB}, 10 por defecto; {@code
 *     SKYNET_ARTIFACT_STAGE_MAX_MB}, 50; {@code SKYNET_ARTIFACT_STAGE_MAX_FILES}, 50)
 * @param repoRoots carpetas en las que busca repositorios git para el asistente «Importar proyecto»
 *     ({@code SKYNET_REPO_ROOTS}, separadas por {@code :}; IP-A); vacío para no buscar
 * @param repoScan cada cuánto vuelve a buscarlos ({@code SKYNET_REPO_SCAN}, {@code 10m} por
 *     defecto; {@code off} busca solo al arrancar y cuando se pide), o {@code null}
 */
public record RunnerConfig(
    URI controlPlane,
    String name,
    String registrationToken,
    int capacity,
    Path home,
    String claudeExecutable,
    String codexExecutable,
    List<String> extraEnv,
    Duration pollWait,
    Duration heartbeatInterval,
    Duration cancelGrace,
    Path modelPrices,
    Duration logRetention,
    Set<String> providers,
    String agyExecutable,
    Duration quotaPoll,
    DeclaredFiles.Limits declaredFiles,
    List<Path> repoRoots,
    Duration repoScan) {

  public RunnerConfig {
    declaredFiles = declaredFiles == null ? DeclaredFiles.Limits.DEFAULT : declaredFiles;
    repoRoots = repoRoots == null ? List.of() : List.copyOf(repoRoots);
  }

  /** Configuración sin carpetas de repositorios: el runner no manda catálogo. */
  public RunnerConfig(
      URI controlPlane,
      String name,
      String registrationToken,
      int capacity,
      Path home,
      String claudeExecutable,
      String codexExecutable,
      List<String> extraEnv,
      Duration pollWait,
      Duration heartbeatInterval,
      Duration cancelGrace,
      Path modelPrices,
      Duration logRetention,
      Set<String> providers,
      String agyExecutable,
      Duration quotaPoll,
      DeclaredFiles.Limits declaredFiles) {
    this(
        controlPlane,
        name,
        registrationToken,
        capacity,
        home,
        claudeExecutable,
        codexExecutable,
        extraEnv,
        pollWait,
        heartbeatInterval,
        cancelGrace,
        modelPrices,
        logRetention,
        providers,
        agyExecutable,
        quotaPoll,
        declaredFiles,
        List.of(),
        null);
  }

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
        env.getOrDefault("SKYNET_CODEX_BIN", "codex"),
        Arrays.stream(env.getOrDefault("SKYNET_AGENT_ENV", "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList(),
        Duration.ofSeconds(25),
        Duration.ofSeconds(15),
        Duration.ofSeconds(10),
        env.containsKey("SKYNET_MODEL_PRICES") ? Path.of(env.get("SKYNET_MODEL_PRICES")) : null,
        Duration.ofDays(Long.parseLong(env.getOrDefault("SKYNET_LOG_RETENTION_DAYS", "7"))),
        providers(env.get("SKYNET_PROVIDERS")),
        env.getOrDefault("SKYNET_AGY_BIN", "agy"),
        interval("SKYNET_QUOTA_POLL", env.getOrDefault("SKYNET_QUOTA_POLL", "10m")),
        new DeclaredFiles.Limits(
            megabytes(env, "SKYNET_ARTIFACT_FILE_MAX_MB", 10),
            megabytes(env, "SKYNET_ARTIFACT_STAGE_MAX_MB", 50),
            Integer.parseInt(env.getOrDefault("SKYNET_ARTIFACT_STAGE_MAX_FILES", "50"))),
        repoRoots(env.get("SKYNET_REPO_ROOTS")),
        interval("SKYNET_REPO_SCAN", env.getOrDefault("SKYNET_REPO_SCAN", "10m")));
  }

  /**
   * Las carpetas de {@code SKYNET_REPO_ROOTS}: separadas por {@code :}, con {@code ~} como la
   * carpeta personal. Las relativas se ignoran: dependerían de dónde arranque el servicio.
   */
  static List<Path> repoRoots(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    String home = System.getProperty("user.home");
    return Arrays.stream(value.split(":"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.equals("~") ? home : s.startsWith("~/") ? home + s.substring(1) : s)
        .map(Path::of)
        .filter(Path::isAbsolute)
        .map(Path::normalize)
        .distinct()
        .toList();
  }

  private static long megabytes(Map<String, String> env, String name, long defaultValue) {
    return Long.parseLong(env.getOrDefault(name, Long.toString(defaultValue))) * 1024 * 1024;
  }

  /** {@code 30s}, {@code 10m} o {@code 2h}; {@code off} (o {@code 0}) es {@code null}. */
  static Duration interval(String value) {
    return interval("SKYNET_QUOTA_POLL", value);
  }

  /** {@link #interval(String)} de la variable {@code variable}, que nombra si no se entiende. */
  static Duration interval(String variable, String value) {
    String v = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (v.equals("off") || v.equals("0")) {
      return null;
    }
    if (!v.matches("\\d+[smh]")) {
      throw new IllegalArgumentException(
          variable + ": " + value + " no es un intervalo (p. ej. 10m, 30s, 1h u off)");
    }
    long n = Long.parseLong(v.substring(0, v.length() - 1));
    return switch (v.charAt(v.length() - 1)) {
      case 's' -> Duration.ofSeconds(n);
      case 'm' -> Duration.ofMinutes(n);
      default -> Duration.ofHours(n);
    };
  }

  private static Set<String> providers(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  private static String hostname() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "runner";
    }
  }
}
