package dev.skynet.runner.supervisor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Proceso lanzado por {@link ProcessSupervisor}. */
public final class SupervisedProcess {

  private static final Duration POLL = Duration.ofMillis(20);

  private final Process process;
  private final Thread stdoutReader;
  private final StderrTail stderr;
  private final boolean groupLeader;
  private volatile String signal;

  SupervisedProcess(Process process, Thread stdoutReader, StderrTail stderr, boolean groupLeader) {
    this.process = process;
    this.stdoutReader = stdoutReader;
    this.stderr = stderr;
    this.groupLeader = groupLeader;
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
   * padre pasan a colgar de init; si el proceso lidera su grupo, se incluye el grupo entero.
   */
  public void terminate(Duration grace) {
    if (!process.isAlive()) {
      return;
    }
    signal = "SIGTERM";
    if (terminateTree(process.toHandle(), groupLeader, grace)) {
      signal = "SIGKILL";
    }
  }

  /**
   * Espera a que termine y a que se haya leído toda su salida estándar. Si lideraba su grupo,
   * termina lo que el agente dejara vivo en él (procesos en segundo plano), que además podría
   * mantener abierta su salida estándar.
   */
  public ProcessExit awaitExit(Duration grace) throws InterruptedException {
    int code = process.waitFor();
    if (groupLeader) {
      terminateAll(ProcessGroups.members(process.pid()), process.pid(), grace);
    }
    stdoutReader.join();
    stderr.join();
    return new ProcessExit(code, signal, stderr.tail());
  }

  /**
   * Termina un árbol de procesos cualquiera (p. ej. huérfanos de una ejecución anterior).
   *
   * @return {@code true} si hubo que recurrir a SIGKILL
   */
  static boolean terminateTree(ProcessHandle root, boolean groupLeader, Duration grace) {
    long group = groupLeader ? root.pid() : -1;
    Stream<ProcessHandle> tree = Stream.concat(root.descendants(), Stream.of(root));
    if (group > 0) {
      tree = Stream.concat(tree, ProcessGroups.members(group).stream());
    }
    return terminateAll(tree.distinct().toList(), group, grace);
  }

  /**
   * SIGTERM a {@code processes} y SIGKILL a los que sigan vivos pasado {@code grace}. Si {@code
   * group} es positivo, el SIGKILL alcanza también a los miembros del grupo nacidos mientras tanto.
   */
  private static boolean terminateAll(List<ProcessHandle> processes, long group, Duration grace) {
    List<ProcessHandle> tree = processes;
    tree.forEach(ProcessHandle::destroy);
    long deadline = System.nanoTime() + grace.toNanos();
    try {
      while (tree.stream().anyMatch(ProcessGroups::isRunning) && System.nanoTime() < deadline) {
        Thread.sleep(POLL);
      }
    } catch (InterruptedException e) {
      // Sin esperar más: se mata abajo lo que siga vivo.
      Thread.currentThread().interrupt();
    }
    if (group > 0) {
      tree =
          Stream.concat(tree.stream(), ProcessGroups.members(group).stream()).distinct().toList();
    }
    boolean forced = false;
    for (ProcessHandle handle : tree) {
      if (ProcessGroups.isRunning(handle)) {
        handle.destroyForcibly();
        forced = true;
      }
    }
    return forced;
  }
}
