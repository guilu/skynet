package dev.skynet.runner.catalog;

import dev.skynet.protocol.runner.RepositoryCatalog;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Busca los repositorios al arrancar, cada {@code SKYNET_REPO_SCAN} y cuando el asistente pide
 * «Volver a buscar», y manda el catálogo al control plane. Sin carpetas raíz no hace nada.
 */
public final class RepositoryCatalogPublisher implements AutoCloseable {

  /** Quien lleva el catálogo al control plane. */
  @FunctionalInterface
  public interface Sink {
    void send(RepositoryCatalog catalog) throws IOException, InterruptedException;
  }

  private static final Logger LOG = Logger.getLogger(RepositoryCatalogPublisher.class.getName());

  private final RepositoryScanner scanner;
  private final Duration interval;
  private final Sink sink;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("repository-catalog").factory());

  /**
   * @param interval cada cuánto se busca; {@code null} para buscar solo al arrancar y cuando se
   *     pida
   */
  public RepositoryCatalogPublisher(RepositoryScanner scanner, Duration interval, Sink sink) {
    this.scanner = scanner;
    this.interval = interval;
    this.sink = sink;
  }

  /** Un publicador que nunca busca: el runner no tiene carpetas raíz. */
  public static RepositoryCatalogPublisher disabled() {
    return new RepositoryCatalogPublisher(
        new RepositoryScanner(java.util.List.of(), null), null, catalog -> {});
  }

  public boolean enabled() {
    return !scanner.roots().isEmpty();
  }

  public void start() {
    if (!enabled()) {
      return;
    }
    if (interval == null) {
      executor.execute(this::publish);
    } else {
      executor.scheduleWithFixedDelay(this::publish, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** Busca ya, sin esperar al intervalo. */
  public void refresh() {
    if (enabled() && !executor.isShutdown()) {
      executor.execute(this::publish);
    }
  }

  void publish() {
    try {
      RepositoryCatalog catalog = scanner.scan();
      sink.send(catalog);
      LOG.fine(() -> "Catálogo enviado: " + catalog.repositories().size() + " repositorios");
    } catch (IOException e) {
      LOG.log(Level.FINE, "No se pudo enviar el catálogo de repositorios", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "No se pudo buscar los repositorios", e);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
