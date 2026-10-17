package dev.skynet.runner.quota;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Ejecuta un CLI con un tiempo máximo y devuelve su salida estándar. */
final class CliProcess {

  private CliProcess() {}

  static String run(List<String> command, Map<String, String> env, Duration timeout)
      throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    builder.environment().putAll(env);
    Process process = builder.start();
    try {
      process.getOutputStream().close();
      // La salida se lee aparte: un CLI que no termina no deja colgada la consulta.
      CompletableFuture<byte[]> output =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException(command.getFirst() + " no respondió en " + timeout);
      }
      if (process.exitValue() != 0) {
        throw new IOException(command.getFirst() + " terminó con " + process.exitValue());
      }
      return new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    } catch (ExecutionException | TimeoutException e) {
      throw new IOException("No se pudo leer la salida de " + command.getFirst(), e);
    } finally {
      process.destroyForcibly();
    }
  }
}
