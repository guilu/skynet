/**
 * Registro de eventos append-only y su difusión en tiempo real por SSE.
 *
 * <p>Los eventos llevan una secuencia global sin huecos y en orden de commit, de modo que un
 * cliente puede reanudar desde el último evento recibido ({@code Last-Event-ID}) sin perder nada.
 */
package dev.skynet.controlplane.event;
