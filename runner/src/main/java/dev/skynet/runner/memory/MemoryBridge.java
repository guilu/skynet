package dev.skynet.runner.memory;

import java.util.List;
import java.util.Objects;

/**
 * El servidor MCP de Skynet de una invocación: cómo lo lanza el CLI del agente. Va por stdio y
 * habla con el control plane con el token de la invocación, que lee de un fichero: el token nunca
 * aparece en la línea de comandos ni en el prompt. Es el de memoria ({@link #SERVER}) en las fases
 * y el de Skynet ({@code skynet}, CH-C) en los turnos del chat con Skyny.
 *
 * @param server nombre del servidor en la configuración del CLI
 * @param command ejecutable y argumentos del servidor ({@code skynet-runner memory-mcp …})
 */
public record MemoryBridge(String server, List<String> command) {

  /**
   * Nombre del servidor en la configuración del CLI: sus herramientas son {@code mcp__<NAME>__…}.
   */
  public static final String SERVER = "skynet_memoria";

  /** El de memoria. */
  public MemoryBridge(List<String> command) {
    this(SERVER, command);
  }

  public MemoryBridge {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(command, "command");
    if (command.isEmpty()) {
      throw new IllegalArgumentException("Falta el comando del servidor de memoria");
    }
    command = List.copyOf(command);
  }

  /** Sus herramientas, para {@code --allowedTools}: {@code mcp__<server>}. */
  public String tools() {
    return "mcp__" + server;
  }

  public String executable() {
    return command.getFirst();
  }

  public List<String> args() {
    return command.subList(1, command.size());
  }
}
