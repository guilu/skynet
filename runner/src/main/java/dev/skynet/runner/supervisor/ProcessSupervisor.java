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
 *   <li>Terminar mata todo el árbol de procesos.
 * </ul>
 */
public class ProcessSupervisor {

  private static final File NULL_DEVICE =
      new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");

  public SupervisedProcess start(
      List<String> command,
      Path workingDirectory,
      Map<String, String> environment,
      Path rawLog,
      Consumer<String> stdoutLine)
      throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(command)
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
    return new SupervisedProcess(process, reader, stderr);
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
    orphan.ifPresent(h -> SupervisedProcess.terminateTree(h, grace));
    return orphan.isPresent();
  }
}
