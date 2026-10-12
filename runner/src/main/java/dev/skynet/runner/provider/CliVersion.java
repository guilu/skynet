package dev.skynet.runner.provider;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Pregunta su versión a un CLI ({@code <ejecutable> --version}). */
public final class CliVersion {

  private CliVersion() {}

  /**
   * Primera línea de {@code --version}, o vacío si el CLI no está, no responde en 10 s o termina
   * con error.
   */
  public static Optional<String> of(String executable, Map<String, String> env) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(List.of(executable, "--version")).redirectErrorStream(true);
      builder.environment().clear();
      builder.environment().putAll(env);
      Process process = builder.start();
      process.getOutputStream().close();
      String output = new String(process.getInputStream().readAllBytes()).trim();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      if (process.exitValue() != 0) {
        return Optional.empty();
      }
      return Optional.of(output.lines().findFirst().orElse(output));
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
