package dev.skynet.runner.memory;

import java.util.List;
import java.util.Objects;

/**
 * El servidor MCP de memoria de una invocación: cómo lo lanza el CLI del agente. Va por stdio y
 * habla con el control plane con el token de memoria de la invocación, que lee de un fichero: el
 * token nunca aparece en la línea de comandos ni en el prompt.
 *
 * @param command ejecutable y argumentos del servidor ({@code skynet-runner memory-mcp …})
 */
public record MemoryBridge(List<String> command) {

  /**
   * Nombre del servidor en la configuración del CLI: sus herramientas son {@code mcp__<NAME>__…}.
   */
  public static final String SERVER = "skynet_memoria";

  public MemoryBridge {
    Objects.requireNonNull(command, "command");
    if (command.isEmpty()) {
      throw new IllegalArgumentException("Falta el comando del servidor de memoria");
    }
    command = List.copyOf(command);
  }

  public String executable() {
    return command.getFirst();
  }

  public List<String> args() {
    return command.subList(1, command.size());
  }
}
