package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Conversación de una sesión: sus invocaciones encadenadas, de la primera a la última, con lo que
 * se le pidió al agente en cada una y lo que respondió. Un fork arrastra la conversación de la que
 * parte; un reintento empieza una nueva.
 */
public record ConversationView(List<Turn> turns) {

  /**
   * Una invocación de la conversación.
   *
   * @param workflowRunId ejecución en la que vive la invocación
   * @param prompt prompt o mensaje con el que se lanzó
   * @param messages bloques de texto del agente principal (sin los de subagentes)
   */
  public record Turn(
      UUID workflowRunId, AgentRunView agent, String prompt, List<Message> messages) {}

  public record Message(long sequence, Instant occurredAt, String text) {}
}
