/**
 * Ejecuciones de workflow, fases y agentes, con sus máquinas de estados.
 *
 * <p>Una ejecución es una versión publicada de un workflow lanzada sobre un trabajo y un
 * repositorio; {@code adhoc} es el de una única fase con un agente. Este módulo aplica los cambios
 * con sus eventos ({@link dev.skynet.controlplane.workflow.RunSteps}) y avisa de cada cambio con
 * {@link dev.skynet.controlplane.workflow.WorkflowRunChanged}; qué fase avanza lo decide el módulo
 * {@code engine}.
 */
package dev.skynet.controlplane.workflow;
