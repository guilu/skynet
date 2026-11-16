package dev.skynet.controlplane.chat;

/**
 * Una tarjeta que acompaña al texto de un mensaje: una ejecución, una entrada de memoria, una
 * acción por confirmar (CH-B, CH-D).
 *
 * @param kind qué es: {@code workflow_run}, {@code memory}, {@code action}…
 * @param id la fila a la que apunta
 * @param label cómo se nombra
 * @param url el enlace dentro de la web, si lo hay
 */
public record ChatCard(String kind, String id, String label, String url) {}
