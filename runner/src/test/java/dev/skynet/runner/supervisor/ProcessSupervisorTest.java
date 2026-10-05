package dev.skynet.runner.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class ProcessSupervisorTest {

  @TempDir Path dir;
  private final ProcessSupervisor supervisor = new ProcessSupervisor();
  private static final Map<String, String> ENV = Map.of("PATH", System.getenv("PATH"));
  private static final Duration GRACE = Duration.ofSeconds(1);

  @Test
  void deliversStdoutLinesInOrderAndKeepsARawLog() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "echo uno; echo dos; echo error >&2; exit 3"),
            dir,
            ENV,
            dir.resolve("logs/raw.ndjson"),
            lines::add);

    ProcessExit exit = p.awaitExit(GRACE);

    assertThat(exit.exitCode()).isEqualTo(3);
    assertThat(exit.signal()).isNull();
    assertThat(exit.stderrTail()).isEqualTo("error\n");
    assertThat(lines).containsExactly("uno", "dos");
    assertThat(Files.readAllLines(dir.resolve("logs/raw.ndjson"))).containsExactly("uno", "dos");
  }

  @Test
  void stdinIsClosedAndTheEnvironmentIsNotInherited() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "cat; echo \"home=${HOME:-none}\""),
            dir,
            ENV,
            dir.resolve("raw"),
            lines::add);

    // Con stdin abierto, cat se quedaría esperando.
    assertThat(p.awaitExit(GRACE).exitCode()).isZero();
    assertThat(lines).containsExactly("home=none");
  }

  @Test
  void terminateKillsTheWholeProcessTree() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "sleep 60 & echo $!; wait"),
            dir,
            ENV,
            dir.resolve("raw"),
            lines::add);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (lines.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    long child = Long.parseLong(lines.getFirst());
    assertThat(ProcessHandle.of(child)).isPresent();

    p.terminate(Duration.ofSeconds(2));
    ProcessExit exit = p.awaitExit(GRACE);

    assertThat(exit.signal()).isIn("SIGTERM", "SIGKILL");
    assertThat(running(child)).isFalse();
  }

  @Test
  void launchesTheAgentAsTheLeaderOfItsOwnProcessGroup() throws Exception {
    assumeTrue(supervisor.usesProcessGroups(), "setsid no está disponible");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "echo $$; sleep 60"), dir, ENV, dir.resolve("raw"), lines::add);

    assertThat(Long.parseLong(firstLine(lines))).isEqualTo(p.pid());
    assertThat(ProcessGroups.groupOf(p.pid())).hasValue(p.pid());
    p.terminate(GRACE);
    p.awaitExit(GRACE);
  }

  @Test
  void terminateAlsoKillsADescendantThatDetachedFromTheTree() throws Exception {
    assumeTrue(supervisor.usesProcessGroups(), "setsid no está disponible");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    // El subshell termina en cuanto lanza sleep: el nieto queda colgando de init, fuera del árbol.
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "(sleep 60 >/dev/null 2>&1 & echo $!); sleep 60"),
            dir,
            ENV,
            dir.resolve("raw"),
            lines::add);
    long grandchild = Long.parseLong(firstLine(lines));
    assertThat(ProcessHandle.current().descendants().map(ProcessHandle::pid))
        .doesNotContain(grandchild);

    p.terminate(GRACE);
    p.awaitExit(GRACE);

    assertThat(running(grandchild)).isFalse();
  }

  @Test
  void whenTheAgentExitsItsBackgroundProcessesAreTerminated() throws Exception {
    assumeTrue(supervisor.usesProcessGroups(), "setsid no está disponible");
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    // El proceso en segundo plano hereda stdout: sin matarlo, la lectura no terminaría nunca.
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "sleep 60 & echo $!"), dir, ENV, dir.resolve("raw"), lines::add);

    ProcessExit exit = p.awaitExit(GRACE);

    assertThat(exit.exitCode()).isZero();
    long background = Long.parseLong(firstLine(lines));
    assertThat(running(background)).isFalse();
  }

  @Test
  void withoutSetsidItStillKillsTheTree() throws Exception {
    ProcessSupervisor plain = new ProcessSupervisor(null);
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    SupervisedProcess p =
        plain.start(
            List.of("sh", "-c", "sleep 60 & echo $!; wait"),
            dir,
            ENV,
            dir.resolve("raw"),
            lines::add);
    long child = Long.parseLong(firstLine(lines));
    assertThat(ProcessGroups.groupOf(p.pid())).isNotEqualTo(java.util.OptionalLong.of(p.pid()));

    p.terminate(GRACE);
    p.awaitExit(GRACE);

    assertThat(running(child)).isFalse();
  }

  /** Vivo y no zombi: en el contenedor de CI nadie recoge a los huérfanos ya muertos. */
  private static boolean running(long pid) {
    return ProcessHandle.of(pid).filter(ProcessGroups::isRunning).isPresent();
  }

  /** Primera línea de stdout, sin esperar más de 5 s. */
  private static String firstLine(BlockingQueue<String> lines) throws InterruptedException {
    String line = lines.poll(5, TimeUnit.SECONDS);
    assertThat(line).as("primera línea de stdout").isNotNull();
    return line;
  }

  @Test
  void escalatesToSigkillWhenSigtermIsIgnored() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    SupervisedProcess p =
        supervisor.start(
            List.of("sh", "-c", "trap '' TERM; echo ready; while true; do sleep 0.1; done"),
            dir,
            ENV,
            dir.resolve("raw"),
            lines::add);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (lines.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    p.terminate(Duration.ofMillis(300));

    assertThat(p.awaitExit(GRACE).signal()).isEqualTo("SIGKILL");
  }

  @Test
  void terminatesOrphansOnlyIfStartTimeMatches() throws Exception {
    Process orphan = new ProcessBuilder("sleep", "60").start();
    var started = orphan.info().startInstant().orElseThrow();

    assertThat(
            supervisor.terminateOrphan(
                orphan.pid(), started.minusSeconds(60), Duration.ofSeconds(1)))
        .isFalse();
    assertThat(orphan.isAlive()).isTrue();
    assertThat(supervisor.terminateOrphan(orphan.pid(), started, Duration.ofSeconds(1))).isTrue();
    orphan.waitFor();
    assertThat(orphan.isAlive()).isFalse();
  }
}
