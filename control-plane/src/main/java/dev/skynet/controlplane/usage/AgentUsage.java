package dev.skynet.controlplane.usage;

/**
 * Lo que cuesta un agente en la cuota de su CLI ({@code GET /api/usage/agents}): sus invocaciones
 * lanzadas en el periodo.
 *
 * @param agent el agente del YAML, o la fase si no tiene ({@code agent} en el agente suelto)
 * @param runs invocaciones
 * @param succeeded las que terminaron bien
 * @param fiveHour parte de ventanas de 5 h atribuida, de 0 a 1 (puede pasar de 1 sumando ventanas)
 * @param sevenDay parte de ventanas semanales atribuida
 * @param sevenDayPerRun {@code sevenDay} por invocación
 * @param sevenDayPerSuccess {@code sevenDay} por invocación que terminó bien, o {@code null} si
 *     ninguna terminó bien
 */
public record AgentUsage(
    String workflowKey,
    String workflowName,
    String agent,
    String provider,
    String providerName,
    long runs,
    long succeeded,
    double fiveHour,
    double sevenDay,
    double sevenDayPerRun,
    Double sevenDayPerSuccess,
    Long inputTokens,
    Long outputTokens) {}
