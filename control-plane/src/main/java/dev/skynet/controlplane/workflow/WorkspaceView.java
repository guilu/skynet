package dev.skynet.controlplane.workflow;

import java.util.UUID;

/** Worktree de una invocación en la máquina de su runner. */
public record WorkspaceView(
    UUID id, UUID runnerId, String path, String branch, String baseCommit) {}
