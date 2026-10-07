package dev.skynet.runner.agent;

import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.transport.ArtifactRejectedException;
import dev.skynet.runner.transport.ControlPlaneClient;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sube los artefactos pendientes del journal, de uno en uno y en orden. Un artefacto solo se olvida
 * cuando el control plane lo tiene o lo rechaza; si la conexión falla, se reintenta. La subida es
 * idempotente, así que repetirla es seguro.
 */
public final class ArtifactSender implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(ArtifactSender.class.getName());
  private static final int BATCH = 20;

  private final Journal journal;
  private final ControlPlaneClient client;
  private final Duration idle;
  private final Duration retry;
  private final Object signal = new Object();
  private boolean pending;
  private volatile boolean running = true;
  private final Thread thread;

  public ArtifactSender(Journal journal, ControlPlaneClient client, Duration idle, Duration retry) {
    this.journal = journal;
    this.client = client;
    this.idle = idle;
    this.retry = retry;
    this.thread = Thread.ofPlatform().daemon().name("artifact-sender").unstarted(this::loop);
  }

  public void start() {
    thread.start();
  }

  /** Avisa de que hay artefactos nuevos. */
  public void wakeUp() {
    synchronized (signal) {
      pending = true;
      signal.notifyAll();
    }
  }

  /** Intenta subir ya lo que quede pendiente. Devuelve {@code true} si no queda nada. */
  public boolean flush() {
    try {
      while (true) {
        List<Journal.PendingArtifact> batch = journal.pendingArtifacts(BATCH);
        if (batch.isEmpty()) {
          return true;
        }
        for (Journal.PendingArtifact artifact : batch) {
          upload(artifact);
        }
      }
    } catch (IOException e) {
      LOG.log(Level.FINE, "No se pudieron subir artefactos", e);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void upload(Journal.PendingArtifact artifact) throws IOException, InterruptedException {
    if (Files.exists(artifact.file())) {
      try {
        client.uploadArtifact(artifact.upload(), artifact.file());
      } catch (ArtifactRejectedException e) {
        LOG.warning(
            () -> "Artefacto " + artifact.upload().name() + " rechazado: " + e.getMessage());
      }
    } else {
      LOG.warning(() -> "Falta el contenido del artefacto " + artifact.file() + "; se descarta");
    }
    journal.artifactDelivered(artifact.id());
    Files.deleteIfExists(artifact.file());
  }

  private void loop() {
    while (running) {
      if (!flush()) {
        sleep(retry);
        continue;
      }
      synchronized (signal) {
        long deadline = System.nanoTime() + idle.toNanos();
        long remaining = idle.toMillis();
        while (!pending && running && remaining > 0) {
          try {
            signal.wait(remaining);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          remaining =
              java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        }
        pending = false;
      }
    }
  }

  private void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
  }
}
