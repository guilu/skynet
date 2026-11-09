package dev.skynet.controlplane.definition;

import java.util.List;

/**
 * Lo que el workflow suma a las reglas de riesgo (AU-A): {@code risk} en la raíz del YAML. Las
 * rutas y los patrones solo suben a nivel 2; no se puede bajar nada de nivel.
 *
 * @param paths globs del repositorio que son nivel 2
 * @param patterns expresiones regulares que suben a nivel 2 una línea añadida en código
 */
public record RiskSpec(List<String> paths, List<String> patterns) {

  /** Sin nada que sumar. */
  public static final RiskSpec NONE = new RiskSpec(List.of(), List.of());

  public RiskSpec {
    paths = paths == null ? List.of() : List.copyOf(paths);
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
  }
}
