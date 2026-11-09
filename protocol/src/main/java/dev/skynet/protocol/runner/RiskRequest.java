package dev.skynet.protocol.runner;

import java.util.List;

/**
 * Lo que pide el control plane al runner para medir el nivel de riesgo del cambio de un agente
 * (AU-A): las rutas y los patrones de nivel 2 del workflow y del repositorio, que se suman a {@link
 * RiskRules}. El runner deja el resultado en el artefacto {@code GIT_CHANGES}, bajo {@code risk}.
 *
 * @param paths globs relativos al worktree que suben a nivel 2
 * @param patterns expresiones regulares que suben a nivel 2 una línea añadida en código
 * @param base el commit desde el que se mide (AU-B: el que vio el juez, para medir lo que cambió
 *     después); {@code null} para medir desde el inicio de la invocación
 */
public record RiskRequest(List<String> paths, List<String> patterns, String base) {

  /** Solo las reglas de siempre. */
  public static final RiskRequest DEFAULTS = new RiskRequest(List.of(), List.of());

  public RiskRequest {
    paths = paths == null ? List.of() : List.copyOf(paths);
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
    if (base != null && !base.matches("[0-9a-f]{7,64}")) {
      throw new IllegalArgumentException("El commit base del riesgo no es un hash: " + base);
    }
  }

  /** Medido desde el inicio de la invocación. */
  public RiskRequest(List<String> paths, List<String> patterns) {
    this(paths, patterns, null);
  }

  /** Las mismas reglas, medidas desde {@code commit}. */
  public RiskRequest from(String commit) {
    return new RiskRequest(paths, patterns, commit);
  }
}
