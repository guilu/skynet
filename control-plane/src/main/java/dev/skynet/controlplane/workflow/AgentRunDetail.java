package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.UUID;

record AgentRunDetail(AgentRunView agent, UUID workflowRunId, List<PromptView> prompts) {}
