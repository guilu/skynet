package dev.skynet.controlplane.workflow;

import java.util.UUID;

/** La PR de una ejecución quedó fusionada, por Skynet o a mano en GitHub (MV-A). */
record PullRequestMerged(UUID workflowRunId) {}
