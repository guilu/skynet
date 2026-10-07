package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.runner.RunVerification;
import java.util.UUID;

/**
 * Una verificación ha quedado en cola. Va al runner del worktree. Se publica dentro de la
 * transacción.
 */
public record VerificationQueued(UUID agentRunId, UUID runnerId, RunVerification verify) {}
