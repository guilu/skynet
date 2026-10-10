/**
 * Motor de workflows (W2): decide qué fases de una ejecución pueden empezar, cuáles se cancelan y
 * cuándo termina, y lo aplica con los pasos de {@link dev.skynet.controlplane.workflow.RunSteps}.
 *
 * <p>Cada cambio en una ejecución ({@link dev.skynet.controlplane.workflow.WorkflowRunChanged})
 * deja un trabajo en {@code workflow_job} dentro de su misma transacción y se evalúa allí mismo; si
 * la evaluación falla o tiene que esperar, el trabajo queda para los workers, que lo reclaman con
 * {@code FOR UPDATE SKIP LOCKED} y un plazo de alquiler. Evaluar es idempotente, así que repetirlo
 * tras un reinicio no duplica nada.
 */
package dev.skynet.controlplane.engine;
