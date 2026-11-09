package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.Map;

/**
 * El nivel de riesgo de una fase o de una ejecución (AU-A), con lo que lo fijó.
 *
 * @param tier 0 (documentación), 1 (código) o 2 (migraciones, CI, dependencias, despliegue,
 *     seguridad o secretos)
 * @param summary una línea: «Nivel 2: `V49__risk.sql` (ruta)»
 * @param files ficheros cambiados
 * @param lines líneas añadidas y quitadas, sin tests ni documentación
 * @param reasons por fichero: {@code path}, {@code tier}, {@code kind} y, si lo subió a nivel 2,
 *     {@code label} y {@code match}
 */
public record RiskView(
    int tier, String summary, int files, int lines, List<Map<String, Object>> reasons) {

  public RiskView {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  @SuppressWarnings("unchecked")
  static RiskView of(int tier, Map<String, Object> detail) {
    Map<String, Object> d = detail == null ? Map.of() : detail;
    return new RiskView(
        tier,
        d.get("summary") instanceof String s ? s : "Nivel " + tier,
        d.get("files") instanceof Number n ? n.intValue() : 0,
        d.get("lines") instanceof Number n ? n.intValue() : 0,
        d.get("reasons") instanceof List<?> list
            ? list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList()
            : List.of());
  }
}
