package dev.skynet.runner.provider;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.memory.MemoryBridge;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador de un proveedor de agentes (Claude Code, Codex, Antigravity…). Solo traduce: cómo se
 * llama a su CLI, qué entorno recibe, qué significa su salida y cuánto cuesta. Lanzar, supervisar,
 * cancelar, limitar el tiempo y recoger los artefactos lo hace el runner igual para todos ({@link
 * dev.skynet.runner.agent.AgentExecutor}).
 */
public interface AgentProvider {

  AgentProviderKind kind();

  /** Ejecutable del CLI. */
  String executable();

  /**
   * Línea de comandos de una invocación: arranque, reanudación o fork ({@code start.resume()}).
   *
   * @param files ruta base para los ficheros auxiliares que necesite el CLI (p. ej. {@code
   *     <files>.schema.json}); están junto a los logs de la invocación y se borran con ellos
   */
  List<String> command(StartAgent start, Path files) throws IOException;

  /**
   * Si el CLI puede recibir el servidor MCP de memoria ({@link #command(StartAgent, Path,
   * MemoryBridge)}).
   */
  default boolean supportsMemory() {
    return false;
  }

  /**
   * Línea de comandos con el servidor MCP de memoria de la invocación, o sin él con {@code memory}
   * nulo. Los proveedores que no lo admiten lo ignoran.
   */
  default List<String> command(StartAgent start, Path files, MemoryBridge memory)
      throws IOException {
    return command(start, files);
  }

  /**
   * Entorno de una invocación: las variables básicas y las de este proveedor y, de las adicionales
   * que permite el runner, solo las que pide la política del repositorio ({@code null}: todas).
   */
  Map<String, String> environment(Map<String, String> runnerEnv, List<String> requested);

  /** Variables que pide la invocación y el runner no permite: no las recibe el agente. */
  List<String> refusedEnvironment(StartAgent start);

  /** Traductor de la salida de una invocación nueva. */
  EventParser newParser(StartAgent start);

  /** Estimador de coste de una invocación nueva. */
  CostMeter newCostMeter(StartAgent start);

  /**
   * Deja la sesión {@code sessionId} del worktree {@code from} lista para continuarla desde {@code
   * to} (fork). Los proveedores que guardan sus sesiones por directorio la copian.
   *
   * @return {@code false} si no la encontró; el CLI lo dirá al continuarla
   */
  default boolean copySession(String sessionId, Path from, Path to) throws IOException {
    return true;
  }

  /**
   * Eventos que el runner añade cuando termina el proceso, con lo que el CLI no dice en su salida:
   * p. ej. la cuota de Codex, que está en el fichero de su sesión ({@code agent.quota}). Nunca
   * falla: lo que no pueda leer, lo omite.
   *
   * @param parser el traductor de esta invocación, con lo que vio de su salida
   */
  default List<ParsedEvent> afterExit(EventParser parser, Map<String, String> runnerEnv) {
    return List.of();
  }

  /** Versión del CLI instalado ({@code --version}), si responde. */
  Optional<String> version(Map<String, String> runnerEnv);
}
