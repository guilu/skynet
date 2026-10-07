package dev.skynet.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tipos de evento normalizado de un agente, independientes del proveedor.
 *
 * <p>El mapeo desde el NDJSON de Claude Code está en {@code docs/claude-code-stream-json.md}. El
 * nombre en el cable ({@link #wireName()}) es el que se persiste en {@code event.event_type}.
 */
public enum AgentEventType {
  /** El proveedor ha iniciado la sesión: id de sesión, modelo, versión y herramientas. */
  SESSION_STARTED("agent.session.started"),
  /** Bloque de texto completo del asistente; los bloques de un mismo mensaje comparten id. */
  MESSAGE_RECEIVED("agent.message.received"),
  /** El agente invoca una herramienta. */
  TOOL_STARTED("agent.tool.started"),
  /** Resultado de una herramienta. */
  TOOL_COMPLETED("agent.tool.completed"),
  /** Una herramienta de edición ha modificado un archivo. */
  FILE_CHANGED("agent.file.changed"),
  /** El proveedor ha denegado el uso de una herramienta. */
  PERMISSION_DENIED("agent.permission.denied"),
  /** El agente ha cambiado el estado de git (p. ej. un commit); pendiente de verificar con git. */
  VCS_CHANGED("agent.vcs.changed"),
  /** La cuenta del proveedor está limitada. */
  RATE_LIMIT("agent.rate_limit"),
  /** Resultado final de la invocación: turnos, tokens, coste acumulado y salida estructurada. */
  RESULT("agent.result"),
  /** El runner ha preparado el worktree: {@code path}, {@code branch} y {@code baseCommit}. */
  WORKSPACE_READY("agent.workspace.ready"),
  /**
   * Fin del proceso del agente, emitido por el runner: {@code exitCode} y, si lo mató una señal,
   * {@code signal} (p. ej. {@code SIGTERM}). Si el proceso no llegó a arrancar, {@code exitCode}
   * falta y {@code error} explica por qué. Es el evento que decide el estado final.
   */
  PROCESS_EXITED("agent.process.exited"),
  /**
   * El runner ha empezado a ejecutar el comando de verificación de un worktree: {@code
   * verificationRunId}. No cambia el estado del agente.
   */
  VERIFICATION_STARTED("agent.verification.started"),
  /**
   * Fin de la verificación: {@code verificationRunId}, {@code exitCode} (o {@code signal}), {@code
   * tests} ({@code total}, {@code failed}, {@code errors}, {@code skipped}) si se encontraron
   * informes JUnit, y {@code error} si el comando no se pudo ejecutar o se agotó su tiempo.
   */
  VERIFICATION_COMPLETED("agent.verification.completed"),
  /**
   * Resultado de una orden {@code CLEANUP}: {@code workspaceId}, {@code path} y, si no se pudo
   * eliminar, {@code error}. No cambia el estado del agente.
   */
  WORKSPACE_REMOVED("agent.workspace.removed"),
  /** Línea que el adaptador no reconoce; se conserva para no perder información. */
  RAW("agent.raw");

  private static final Map<String, AgentEventType> BY_WIRE_NAME =
      Arrays.stream(values()).collect(Collectors.toMap(t -> t.wireName, Function.identity()));

  private final String wireName;

  AgentEventType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** Eventos de una verificación: se registran aunque el agente haya terminado. */
  public boolean isVerification() {
    return this == VERIFICATION_STARTED || this == VERIFICATION_COMPLETED;
  }

  public static AgentEventType fromWireName(String wireName) {
    AgentEventType type = BY_WIRE_NAME.get(wireName);
    if (type == null) {
      throw new IllegalArgumentException("Tipo de evento desconocido: " + wireName);
    }
    return type;
  }
}
