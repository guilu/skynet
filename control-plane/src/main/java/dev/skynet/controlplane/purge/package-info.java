/**
 * Eliminar lo archivado (AE-B): proyectos, repositorios, trabajos, ejecuciones y runners, con todo
 * lo que cuelga de ellos, en una sola transacción.
 *
 * <p>Solo se elimina lo archivado. Los eventos de lo eliminado se borran (la purga es lo único que
 * puede saltarse el trigger append-only de {@code event}) y queda un evento lápida con lo que se
 * borró. Los blobs que dejan de usarse se borran después del commit. No se elimina nada mientras
 * queden worktrees vivos que solo usa lo que se elimina.
 */
package dev.skynet.controlplane.purge;
