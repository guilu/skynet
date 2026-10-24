package dev.skynet.runner.memory;

import dev.skynet.protocol.runner.MemoryToken;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Prepara la memoria de una invocación: pide su token al control plane, lo deja en un fichero que
 * solo puede leer el usuario del runner y devuelve cómo se lanza el puente MCP.
 */
public final class MemorySessions {

  /** Pide el token de memoria de una invocación. */
  @FunctionalInterface
  public interface TokenSource {
    MemoryToken issue(UUID agentRunId) throws IOException, InterruptedException;
  }

  private final TokenSource tokens;
  private final URI controlPlane;
  private final List<String> runner;

  /**
   * @param runner cómo se lanza este mismo runner ({@code java -cp … RunnerMain}); el puente es
   *     {@code <runner> memory-mcp}
   */
  public MemorySessions(TokenSource tokens, URI controlPlane, List<String> runner) {
    this.tokens = tokens;
    this.controlPlane = controlPlane;
    this.runner = List.copyOf(runner);
  }

  /** El runner de este proceso: el mismo java y el mismo classpath. */
  public static List<String> currentRunner() {
    String java =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    return List.of(
        java, "-cp", System.getProperty("java.class.path"), "dev.skynet.runner.RunnerMain");
  }

  /**
   * Pide el token y lo escribe en {@code tokenFile} ({@code 0600}).
   *
   * @return el puente para la configuración del CLI
   */
  public MemoryBridge open(UUID agentRunId, Path tokenFile)
      throws IOException, InterruptedException {
    MemoryToken token = tokens.issue(agentRunId);
    Files.createDirectories(tokenFile.toAbsolutePath().getParent());
    Files.deleteIfExists(tokenFile);
    try {
      Files.createFile(
          tokenFile,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      Files.createFile(tokenFile);
    }
    Files.writeString(
        tokenFile, token.token(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    List<String> command = new ArrayList<>(runner);
    command.addAll(
        List.of(
            "memory-mcp",
            "--url",
            controlPlane.toString(),
            "--token-file",
            tokenFile.toString(),
            "--mode",
            token.mode().toLowerCase(java.util.Locale.ROOT)));
    return new MemoryBridge(command);
  }
}
