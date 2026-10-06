package dev.skynet.runner.supervisor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Lanza y supervisa los procesos de los agentes.
 *
 * <ul>
 *   <li>stdin siempre cerrado ({@code /dev/null}): si no, Claude Code espera datos y avisa por
 *       stderr.
 *   <li>Entorno explícito: el proceso no hereda el del runner.
 *   <li>Cada línea de stdout se entrega en orden a un consumidor y se copia íntegra a un log.
 *   <li>Si hay {@code setsid}, el proceso se lanza en su propio grupo, y terminarlo mata el grupo
 *       entero además del árbol de procesos: así tampoco sobrevive un descendiente que se haya
 *       desenganchado de su padre.
 * </ul>
 */
public class ProcessSupervisor {

  private static final File NULL_DEVICE =
      new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");

  private final Path setsid;

  /** Usa {@code setsid} si está en el {@code PATH} del runner. */
  public ProcessSupervisor() {
    this(findSetsid(System.getenv("PATH")));
  }

  /**
   * @param setsid ejecutable de {@code setsid}, o {@code null} para no crear grupos de procesos
   */
  public ProcessSupervisor(Path setsid) {
    this.setsid = setsid;
  }

  /** Busca {@code setsid} en un {@code PATH}; {@code null} si no está (p. ej. en macOS). */
  public static Path findSetsid(String path) {
    if (path == null) {
      return null;
    }
    for (String dir : path.split(File.pathSeparator)) {
      Path candidate = Path.of(dir, "setsid");
      if (!dir.isEmpty() && Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** Lanza los procesos en su propio grupo. */
  public boolean usesProcessGroups() {
    return setsid != null;
  }

  public SupervisedProcess start(
      List<String> command,
      Path workingDirectory,
      Map<String, String> environment,
      Path rawLog,
      Consumer<String> stdoutLine)
      throws IOException {
    List<String> launched = command;
    if (setsid != null) {
      // Sin --fork: el hijo de la JVM no lidera ningún grupo, así que setsid hace exec en el mismo
      // pid y ese pid pasa a ser el del grupo.
      launched = new java.util.ArrayList<>(command);
      launched.addFirst(setsid.toString());
    }
    ProcessBuilder builder =
        new ProcessBuilder(launched)
            .directory(workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE));
    builder.environment().clear();
    builder.environment().putAll(environment);
    Files.createDirectories(rawLog.toAbsolutePath().getParent());
    Process process = builder.start();
    BufferedWriter log = Files.newBufferedWriter(rawLog, StandardCharsets.UTF_8);
    Thread reader =
        Thread.ofPlatform()
            .daemon()
            .name("stdout-" + process.pid())
            .start(() -> pump(process, log, stdoutLine));
    StderrTail stderr = new StderrTail(process.getErrorStream(), "stderr-" + process.pid());
    return new SupervisedProcess(process, reader, stderr, setsid != null);
  }

  private static void pump(Process process, BufferedWriter log, Consumer<String> stdoutLine) {
    try (log;
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        log.write(line);
        log.newLine();
        log.flush();
        stdoutLine.accept(line);
      }
    } catch (IOException e) {
      // El proceso ha terminado o se ha cerrado el log.
    }
  }

  /**
   * Termina un proceso de una ejecución anterior del runner, si sigue vivo y es el mismo (mismo pid
   * y mismo instante de arranque, para no matar un proceso que haya reutilizado el pid).
   *
   * @return {@code true} si había un proceso vivo y se ha terminado
   */
  public boolean terminateOrphan(long pid, Instant startedAt, Duration grace) {
    Optional<ProcessHandle> orphan =
        ProcessHandle.of(pid)
            .filter(ProcessHandle::isAlive)
            .filter(
                h ->
                    h.info()
                        .startInstant()
                        .map(
                            actual ->
                                Duration.between(actual, startedAt)
                                        .abs()
                                        .compareTo(Duration.ofSeconds(2))
                                    <= 0)
                        .orElse(true));
    orphan.ifPresent(
        h -> SupervisedProcess.terminateTree(h, ProcessGroups.isLeader(h.pid()), grace));
    return orphan.isPresent();
  }
}
