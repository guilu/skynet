package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.UUID;

/** Detalle de un agente con su ejecución y sus prompts. */
public record AgentRunDetail(AgentRunView agent, UUID workflowRunId, List<PromptView> prompts) {}
