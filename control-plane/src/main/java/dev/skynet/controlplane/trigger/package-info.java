/**
 * Disparadores (hito DS): lanzar workflows desde fuera de la web. En DS-A, con una etiqueta en una
 * issue de GitHub, en el propio repositorio o en un repositorio de órdenes; en DS-B, con
 * comentarios {@code /skynet} (lanzar, corregir, cancelar, estado) y revisiones que piden cambios;
 * en DS-C, a una hora; y en DL-D, al quedar sano o al fallar la salud un despliegue. Ver {@code
 * docs/disparadores.md}.
 */
package dev.skynet.controlplane.trigger;
