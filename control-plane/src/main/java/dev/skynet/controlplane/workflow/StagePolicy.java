package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageType;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que se le permitirá al agente de una fase si se lanza el workflow en un repositorio: lo que
 * pide su agente del YAML recortado a la política del repositorio.
 *
 * @param stage id de la fase
 * @param name nombre de la fase, o {@code null}
 * @param type tipo de la fase; en una {@code command} solo cuentan el entorno y el comando
 * @param agent agente con nombre, o {@code null} si la fase usa la política del repositorio
 * @param environment variables extra, o {@code null} para todas las que permite el runner
 * @param maxTurns turnos máximos que fija el agente; {@code null}: los del lanzamiento
 * @param maxBudgetUsd presupuesto que fija el agente; {@code null}: el del lanzamiento
 * @param timeoutMinutes tiempo máximo que fija el agente; {@code null}: el del lanzamiento
 * @param model modelo que pide el agente; {@code null}: el global
 * @param command comando de una fase {@code command}, o {@code null}
 */
public record StagePolicy(
    String stage,
    String name,
    StageType type,
    String agent,
    List<String> allowedTools,
    String permissionMode,
    List<String> environment,
    Integer maxTurns,
    BigDecimal maxBudgetUsd,
    Integer timeoutMinutes,
    String model,
    String command) {}
