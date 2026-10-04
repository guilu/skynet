package dev.skynet.runner;

import dev.skynet.runner.agent.AgentExecutor;
import dev.skynet.runner.agent.EventSender;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.transport.ControlPlaneClient;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Punto de entrada del runner local. Se configura con variables de entorno (ver {@link
 * RunnerConfig}) y corre hasta recibir SIGTERM/SIGINT.
 */
public final class RunnerMain {

  private RunnerMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && "--version".equals(args[0])) {
      System.out.println("skynet-runner " + version());
      return;
    }
    RunnerConfig config = RunnerConfig.fromEnvironment(System.getenv());
    CountDownLatch stopRequested = new CountDownLatch(1);
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  stopRequested.countDown();
                  try {
                    stopped.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
    try (Journal journal = Journal.open(config.journalFile())) {
      run(config, journal, stopRequested);
    } finally {
      stopped.countDown();
    }
  }

  private static void run(RunnerConfig config, Journal journal, CountDownLatch stopRequested)
      throws IOException, InterruptedException {
    ControlPlaneClient client = new ControlPlaneClient(config.controlPlane());
    ProcessSupervisor supervisor = new ProcessSupervisor();
    ClaudeCodeProvider provider =
        new ClaudeCodeProvider(config.claudeExecutable(), config.extraEnv());
    try (EventSender sender =
            new EventSender(journal, client, Duration.ofMillis(500), Duration.ofSeconds(2));
        AgentExecutor executor =
            new AgentExecutor(
                journal,
                supervisor,
                new WorkspaceManager(config.workspacesDir()),
                provider,
                System.getenv(),
                config.logsDir(),
                config.cancelGrace(),
                sender::wakeUp);
        RunnerDaemon daemon =
            new RunnerDaemon(
                config,
                client,
                journal,
                executor,
                sender,
                supervisor,
                provider.version(System.getenv()).orElse(null))) {
      daemon.start();
      System.out.println(
          "skynet-runner "
              + version()
              + " conectado a "
              + config.controlPlane()
              + " (capacidad "
              + config.capacity()
              + ")");
      stopRequested.await();
    }
  }

  static String version() {
    String v = RunnerMain.class.getPackage().getImplementationVersion();
    return v != null ? v : "dev";
  }
}
