/**
 * Despliegues (hito DL): qué versión de cada repositorio hay en cada entorno, cómo terminó cada
 * despliegue y, por separado, si lo desplegado está sano. DL-A guarda el modelo y lo enseña; los
 * eventos llegan con GitHub Deployments (DL-B), la salud la comprueba DL-C y la web lo enseña
 * (DL-E). Ver {@code docs/despliegues.md}.
 */
package dev.skynet.controlplane.deployment;
