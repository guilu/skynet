package dev.skynet.controlplane.deployment;

import dev.skynet.controlplane.deployment.HealthChecks.Outcome;
import dev.skynet.controlplane.deployment.HealthChecks.Result;
import dev.skynet.controlplane.deployment.HealthChecks.Task;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hace los intentos de comprobación de salud que tocan (DL-C). Cada uno corre en su propio hilo
 * virtual, para no bloquear al planificador con una aplicación que no contesta.
 *
 * <p>Un intento es un {@code GET} a la URL del entorno, sin seguir redirecciones y con el plazo de
 * la comprobación: bueno si responde 2xx y, si se pide, si el cuerpo contiene el SHA desplegado.
 */
@Component
class HealthProber implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(HealthProber.class);

  /** Intentos que se reclaman por pasada. */
  static final int BATCH = 10;

  /** Lo que se lee del cuerpo como mucho, para buscar el SHA. */
  static final int MAX_BODY = 64 * 1024;

  private static final Duration MARGIN = Duration.ofSeconds(30);

  private final HealthChecks checks;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .proxy(HttpClient.Builder.NO_PROXY)
          .build();

  HealthProber(HealthChecks checks) {
    this.checks = checks;
  }

  @Scheduled(fixedDelayString = "${skynet.deployments.health-poll-interval:2s}")
  void poll() {
    for (Task task : checks.claim(BATCH, MARGIN)) {
      executor.submit(() -> run(task));
    }
  }

  private void run(Task task) {
    try {
      checks.complete(task, probe(task));
    } catch (RuntimeException e) {
      // Si no se pudo registrar, el plazo vence y otro lo retoma.
      log.warn("No se pudo registrar la comprobación de {}: {}", task.deploymentId(), e.toString());
    }
  }

  Result probe(Task task) {
    Duration timeout = Duration.ofSeconds(task.timeoutSeconds());
    long start = System.nanoTime();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(task.url()))
              .timeout(timeout)
              .header("User-Agent", "skynet-health-check")
              .GET()
              .build();
      HttpResponse<InputStream> response =
          http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
              .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      int code = response.statusCode();
      String body;
      try (InputStream in = response.body()) {
        body = new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
      }
      long latency = (System.nanoTime() - start) / 1_000_000;
      if (code / 100 != 2) {
        return new Result(Outcome.BAD_STATUS, code, latency, null);
      }
      if (task.expectSha()
          && !body.toLowerCase(Locale.ROOT).contains(task.commitSha().substring(0, 7))) {
        return new Result(Outcome.SHA_MISMATCH, code, latency, null);
      }
      return new Result(Outcome.OK, code, latency, null);
    } catch (java.util.concurrent.TimeoutException e) {
      return timeout(start);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof HttpTimeoutException) {
        return timeout(start);
      }
      return error(start, cause == null ? e : cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error(start, e);
    } catch (IOException | RuntimeException e) {
      return error(start, e);
    }
  }

  private static Result timeout(long start) {
    return new Result(Outcome.TIMEOUT, null, (System.nanoTime() - start) / 1_000_000, null);
  }

  private static Result error(long start, Throwable e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return new Result(Outcome.ERROR, null, (System.nanoTime() - start) / 1_000_000, message);
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
