package dev.skynet.runner.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class ProcessSupervisorTest {

  @TempDir Path dir;
  private final ProcessSupervisor supervisor = new ProcessSupervisor();
  private static final Map<String, String> ENV = Map.of("PATH", System.getenv("PATH"));

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

    ProcessExit exit = p.awaitExit();

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
    assertThat(p.awaitExit().exitCode()).isZero();
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
    ProcessExit exit = p.awaitExit();

    assertThat(exit.signal()).isIn("SIGTERM", "SIGKILL");
    assertThat(ProcessHandle.of(child).filter(ProcessHandle::isAlive)).isEmpty();
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

    assertThat(p.awaitExit().signal()).isEqualTo("SIGKILL");
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
