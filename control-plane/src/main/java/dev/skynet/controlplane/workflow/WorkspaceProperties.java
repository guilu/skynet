package dev.skynet.controlplane.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retención de los worktrees en los runners.
 *
 * @param retention tiempo desde que termina lo último que trabajó en un worktree hasta que se
 *     elimina ({@code SKYNET_WORKTREE_RETENTION}); su rama se conserva
 */
@ConfigurationProperties("skynet.workspaces")
record WorkspaceProperties(Duration retention) {

  WorkspaceProperties {
    retention = retention == null ? Duration.ofDays(7) : retention;
  }
}
