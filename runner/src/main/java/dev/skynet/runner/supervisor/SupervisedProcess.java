package dev.skynet.runner.supervisor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Proceso lanzado por {@link ProcessSupervisor}. */
public final class SupervisedProcess {

  private final Process process;
  private final Thread stdoutReader;
  private final StderrTail stderr;
  private volatile String signal;

  SupervisedProcess(Process process, Thread stdoutReader, StderrTail stderr) {
    this.process = process;
    this.stdoutReader = stdoutReader;
    this.stderr = stderr;
  }

  public long pid() {
    return process.pid();
  }

  public Instant startedAt() {
    return process.info().startInstant().orElse(Instant.now());
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  /**
   * Termina el proceso y todos sus descendientes: SIGTERM y, si alguno sigue vivo pasado {@code
   * grace}, SIGKILL. Los descendientes se capturan antes de enviar la señal, porque al morir el
   * padre pasan a colgar de init.
   */
  public void terminate(Duration grace) {
    if (!process.isAlive()) {
      return;
    }
    signal = "SIGTERM";
    if (terminateTree(process.toHandle(), grace)) {
      signal = "SIGKILL";
    }
  }

  /** Espera a que termine y a que se haya leído toda su salida estándar. */
  public ProcessExit awaitExit() throws InterruptedException {
    int code = process.waitFor();
    stdoutReader.join();
    stderr.join();
    return new ProcessExit(code, signal, stderr.tail());
  }

  /**
   * Termina un árbol de procesos cualquiera (p. ej. huérfanos de una ejecución anterior).
   *
   * @return {@code true} si hubo que recurrir a SIGKILL
   */
  static boolean terminateTree(ProcessHandle root, Duration grace) {
    List<ProcessHandle> tree =
        java.util.stream.Stream.concat(root.descendants(), java.util.stream.Stream.of(root))
            .toList();
    tree.forEach(ProcessHandle::destroy);
    long deadline = System.nanoTime() + grace.toNanos();
    for (ProcessHandle handle : tree) {
      long remaining = deadline - System.nanoTime();
      try {
        if (remaining > 0) {
          handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
        }
      } catch (Exception e) {
        // Sigue vivo: se mata abajo.
      }
    }
    boolean forced = false;
    for (ProcessHandle handle : tree) {
      if (handle.isAlive()) {
        handle.destroyForcibly();
        forced = true;
      }
    }
    return forced;
  }
}
