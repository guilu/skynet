package dev.skynet.protocol.runner;

import java.util.List;

/**
 * Lo que pide el control plane al runner para medir el nivel de riesgo del cambio de un agente
 * (AU-A): las rutas y los patrones de nivel 2 del workflow y del repositorio, que se suman a {@link
 * RiskRules}. El runner deja el resultado en el artefacto {@code GIT_CHANGES}, bajo {@code risk}.
 *
 * @param paths globs relativos al worktree que suben a nivel 2
 * @param patterns expresiones regulares que suben a nivel 2 una línea añadida en código
 */
public record RiskRequest(List<String> paths, List<String> patterns) {

  /** Solo las reglas de siempre. */
  public static final RiskRequest DEFAULTS = new RiskRequest(List.of(), List.of());

  public RiskRequest {
    paths = paths == null ? List.of() : List.copyOf(paths);
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
  }
}
