/**
 * Runners: registro, latidos, cola de órdenes con long-poll e ingestión de sus eventos
 * (docs/implementation-plan.md §4.3).
 *
 * <p>El runner siempre inicia la conexión (puede estar tras NAT). Las órdenes se guardan en {@code
 * runner_command} y se reparten con {@code FOR UPDATE SKIP LOCKED}; lo que un runner recibe y no
 * confirma se le vuelve a entregar.
 */
package dev.skynet.controlplane.runner;
