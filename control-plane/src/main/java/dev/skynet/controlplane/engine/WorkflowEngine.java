package dev.skynet.controlplane.engine;

import java.util.UUID;

/**
 * Interfaz estrecha del motor, para poder cambiar la implementación (p. ej. por Temporal) sin tocar
 * el resto: le basta con saber que una ejecución ha cambiado.
 */
public interface WorkflowEngine {

  /**
   * La ejecución se ha lanzado, una de sus fases ha terminado o se ha pedido cancelarla: decide y
   * aplica lo que viene después. Se llama dentro de la transacción del cambio.
   */
  void runChanged(UUID workflowRunId);
}
