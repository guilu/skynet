package dev.skynet.runner.supervisor;

/**
 * Cómo terminó un proceso supervisado.
 *
 * @param exitCode código de salida
 * @param signal señal que le enviamos para terminarlo ({@code SIGTERM} o {@code SIGKILL}), o {@code
 *     null} si terminó por sí mismo
 * @param stderrTail últimos bytes de su salida de error
 */
public record ProcessExit(int exitCode, String signal, String stderrTail) {}
