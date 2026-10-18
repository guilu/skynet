package dev.skynet.controlplane.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una de las ejecuciones que más cuota han gastado ({@code GET /api/usage/runs}).
 *
 * @param quota la cuota atribuida a sus agentes, por CLI y ventana
 */
public record RunUsage(
    UUID id,
    String workItemKey,
    String workItemTitle,
    String workflowKey,
    String workflowName,
    String status,
    Instant createdAt,
    List<QuotaShare> quota) {}
