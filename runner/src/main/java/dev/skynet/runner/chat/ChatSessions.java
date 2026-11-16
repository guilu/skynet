package dev.skynet.runner.chat;

import dev.skynet.protocol.runner.ChatToken;
import dev.skynet.protocol.runner.ChatTurn;
import dev.skynet.runner.memory.MemoryBridge;
import dev.skynet.runner.memory.MemorySessions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Prepara los turnos del chat con Skyny (CH-C): la carpeta fija de cada conversación, donde Claude
 * Code guarda su sesión para el siguiente {@code --resume}, y el servidor MCP de Skynet con el
 * token del turno.
 */
public final class ChatSessions {

  /**
   * Nombre del servidor en la configuración del CLI: sus herramientas son {@code mcp__skynet__…}.
   */
  public static final String SERVER = "skynet";

  /** Pide el token del chat de un turno. */
  @FunctionalInterface
  public interface TokenSource {
    ChatToken issue(UUID agentRunId) throws IOException, InterruptedException;
  }

  private final TokenSource tokens;
  private final URI controlPlane;
  private final List<String> runner;
  private final Path root;

  /**
   * @param root carpeta de las conversaciones: una vacía por conversación, {@code
   *     <root>/<conversación>}
   * @param runner cómo se lanza este mismo runner; el servidor es {@code <runner> skynet-mcp}
   */
  public ChatSessions(TokenSource tokens, URI controlPlane, List<String> runner, Path root) {
    this.tokens = tokens;
    this.controlPlane = controlPlane;
    this.runner = List.copyOf(runner);
    this.root = root.toAbsolutePath().normalize();
  }

  /** La carpeta de la conversación, creada si no existe. Nunca es un repositorio. */
  public Path folder(ChatTurn chat) throws IOException {
    Path folder = root.resolve(chat.conversationId().toString());
    Files.createDirectories(folder);
    return folder;
  }

  /**
   * Pide el token del turno y lo escribe en {@code tokenFile} ({@code 0600}).
   *
   * @return el servidor MCP de Skynet para la configuración del CLI
   */
  public MemoryBridge open(UUID agentRunId, Path tokenFile)
      throws IOException, InterruptedException {
    MemorySessions.writeToken(tokenFile, tokens.issue(agentRunId).token());
    List<String> command = new ArrayList<>(runner);
    command.addAll(
        List.of(
            "skynet-mcp", "--url", controlPlane.toString(), "--token-file", tokenFile.toString()));
    return new MemoryBridge(SERVER, command);
  }
}
