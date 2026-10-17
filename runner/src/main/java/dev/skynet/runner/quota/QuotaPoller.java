package dev.skynet.runner.quota;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pregunta su cuota a los CLIs que la dan sin ejecutar nada (Codex y Antigravity), cada {@code
 * SKYNET_QUOTA_POLL} y cuando la página Uso pide «Actualizar». Las muestras esperan aquí hasta el
 * siguiente latido, que las lleva al control plane.
 */
public final class QuotaPoller implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(QuotaPoller.class.getName());

  private final List<QuotaSource> sources;
  private final Duration interval;
  private final List<QuotaSample> pending = new ArrayList<>();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("quota").factory());

  /**
   * @param interval cada cuánto se consulta; {@code null} para no consultar nunca
   */
  public QuotaPoller(List<QuotaSource> sources, Duration interval) {
    this.sources = List.copyOf(sources);
    this.interval = interval;
  }

  public void start() {
    if (interval != null && !sources.isEmpty()) {
      executor.scheduleWithFixedDelay(this::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** Consulta ya, sin esperar al intervalo (también con las consultas apagadas). */
  public void refresh() {
    if (!sources.isEmpty() && !executor.isShutdown()) {
      executor.execute(this::poll);
    }
  }

  /** Consulta todos los CLIs; uno que falla no impide los demás. */
  void poll() {
    for (QuotaSource source : sources) {
      try {
        List<QuotaSample> samples = source.read();
        if (samples.isEmpty()) {
          LOG.fine(() -> source.provider() + " no dio su cuota en un formato conocido");
        }
        add(samples);
      } catch (IOException e) {
        LOG.log(Level.FINE, "No se pudo leer la cuota de " + source.provider(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "La cuota de " + source.provider() + " no se entiende", e);
      }
    }
  }

  /** Las muestras que aún no ha llevado ningún latido; quedan fuera hasta que se devuelvan. */
  public synchronized List<QuotaSample> drain() {
    List<QuotaSample> samples = List.copyOf(pending);
    pending.clear();
    return samples;
  }

  /** Devuelve las muestras de un latido que no llegó, para el siguiente. */
  public synchronized void restore(List<QuotaSample> samples) {
    pending.addAll(0, samples);
  }

  private synchronized void add(List<QuotaSample> samples) {
    pending.addAll(samples);
    // Si el control plane no responde durante mucho tiempo, basta con las últimas.
    if (pending.size() > 200) {
      pending.subList(0, pending.size() - 200).clear();
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
