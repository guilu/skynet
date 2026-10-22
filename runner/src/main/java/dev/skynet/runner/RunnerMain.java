package dev.skynet.runner;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.runner.agent.AgentExecutor;
import dev.skynet.runner.agent.ArtifactSender;
import dev.skynet.runner.agent.ArtifactSpool;
import dev.skynet.runner.agent.EventSender;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.CliVersion;
import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.ProviderRegistry;
import dev.skynet.runner.provider.antigravity.AntigravityProvider;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import dev.skynet.runner.provider.claude.ClaudeSessions;
import dev.skynet.runner.provider.codex.CodexAppServerQuota;
import dev.skynet.runner.provider.codex.CodexProvider;
import dev.skynet.runner.quota.AgyQuota;
import dev.skynet.runner.quota.QuotaPoller;
import dev.skynet.runner.quota.QuotaSource;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.transport.ControlPlaneClient;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    ModelPrices prices =
        config.modelPrices() == null
            ? ModelPrices.defaults()
            : ModelPrices.load(config.modelPrices());
    ClaudeCodeProvider claude =
        new ClaudeCodeProvider(
            config.claudeExecutable(),
            config.extraEnv(),
            prices,
            ClaudeSessions.of(System.getenv()).orElse(null));
    CodexProvider codex = new CodexProvider(config.codexExecutable(), config.extraEnv(), prices);
    AntigravityProvider antigravity =
        new AntigravityProvider(config.agyExecutable(), config.extraEnv(), prices);
    ProviderRegistry providers =
        ProviderRegistry.detect(
            List.of(claude, codex, antigravity), config.providers(), System.getenv());
    try (EventSender sender =
            new EventSender(journal, client, Duration.ofMillis(500), Duration.ofSeconds(2));
        ArtifactSender artifactSender =
            new ArtifactSender(journal, client, Duration.ofSeconds(1), Duration.ofSeconds(5));
        AgentExecutor executor =
            new AgentExecutor(
                journal,
                supervisor,
                new WorkspaceManager(config.workspacesDir()),
                providers,
                System.getenv(),
                config.logsDir(),
                config.cancelGrace(),
                sender::wakeUp,
                new ArtifactSpool(journal, config.artifactsDir(), artifactSender::wakeUp),
                config.declaredFiles());
        RunnerDaemon daemon =
            new RunnerDaemon(
                config,
                client,
                journal,
                executor,
                sender,
                artifactSender,
                supervisor,
                providers.available(),
                quotaPoller(config, providers, codex))) {
      daemon.start();
      System.out.println(
          "skynet-runner "
              + version()
              + " conectado a "
              + config.controlPlane()
              + " (capacidad "
              + config.capacity()
              + "; proveedores: "
              + (providers.available().isEmpty()
                  ? "ninguno"
                  : providers.available().stream()
                      .map(p -> p.id() + (p.version() == null ? "" : " " + p.version()))
                      .collect(java.util.stream.Collectors.joining(", ")))
              + ")");
      stopRequested.await();
    }
  }

  /**
   * Las cuotas que se pueden consultar sin ejecutar nada: Codex ({@code app-server}) si el runner
   * lo tiene, y Antigravity si responde a {@code --version}, aunque el runner no lo declare como
   * proveedor.
   */
  private static QuotaPoller quotaPoller(
      RunnerConfig config, ProviderRegistry providers, CodexProvider codex) {
    List<QuotaSource> sources = new ArrayList<>();
    // Las variables básicas y las de Codex (CODEX_HOME): bastan también para agy.
    Map<String, String> env = codex.environment(System.getenv(), null);
    if (providers.get(AgentProviderKind.CODEX.id()).isPresent()) {
      sources.add(new CodexAppServerQuota(config.codexExecutable(), env, version()));
    }
    if (CliVersion.of(config.agyExecutable(), env).isPresent()) {
      sources.add(new AgyQuota(config.agyExecutable(), env));
    }
    return new QuotaPoller(sources, config.quotaPoll());
  }

  static String version() {
    String v = RunnerMain.class.getPackage().getImplementationVersion();
    return v != null ? v : "dev";
  }
}
