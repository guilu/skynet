package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.usage.QuotaShare;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Tokens y coste de una ejecución: la suma de sus agentes. Un total es {@code null} mientras ningún
 * agente lo haya informado. {@code quota} es la suma de la cuota atribuida a sus agentes, por CLI y
 * ventana (CU-B).
 */
public record RunTotals(
    Long inputTokens,
    Long outputTokens,
    Long cacheReadTokens,
    Long cacheCreationTokens,
    BigDecimal costUsd,
    List<QuotaShare> quota) {

  static RunTotals of(List<AgentRunView> agents) {
    return new RunTotals(
        sum(agents, AgentRunView::inputTokens),
        sum(agents, AgentRunView::outputTokens),
        sum(agents, AgentRunView::cacheReadTokens),
        sum(agents, AgentRunView::cacheCreationTokens),
        agents.stream()
            .map(AgentRunView::costUsd)
            .filter(Objects::nonNull)
            .reduce(BigDecimal::add)
            .orElse(null),
        QuotaShare.sum(agents.stream().flatMap(a -> a.quota().stream()).toList()));
  }

  private static Long sum(List<AgentRunView> agents, Function<AgentRunView, Long> field) {
    return agents.stream().map(field).filter(Objects::nonNull).reduce(Long::sum).orElse(null);
  }
}
