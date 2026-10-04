package dev.skynet.runner.workspace;

import java.nio.file.Path;

/** Worktree aislado de una invocación. */
public record Workspace(Path path, String branch, String baseCommit) {}
