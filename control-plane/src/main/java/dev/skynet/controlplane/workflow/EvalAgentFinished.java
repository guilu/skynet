package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

/** Ha terminado un juez del banco de evaluaciones (EV-C); lo recoge {@link Evals}. */
record EvalAgentFinished(UUID agentRunId, Instant at) {}
