/**
 * Artefactos de las invocaciones y verificaciones (docs/implementation-plan.md §M5): diff, commits,
 * logs, prompt, resultado e informes de tests.
 *
 * <p>Los sube el runner; el control plane redacta los de texto, los recorta al tamaño máximo y
 * guarda el contenido en un {@link dev.skynet.controlplane.artifact.BlobStore} direccionado por su
 * sha256. La web los lista sin descargarlos y lee el contenido por trozos.
 */
package dev.skynet.controlplane.artifact;
