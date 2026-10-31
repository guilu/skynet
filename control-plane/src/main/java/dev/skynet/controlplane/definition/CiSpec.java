package dev.skynet.controlplane.definition;

import java.util.List;

/**
 * Cómo espera la CI una fase {@code github-check} (EN-B): {@code required}, {@code ifNoChecks},
 * {@code fix} y {@code maxRounds} del YAML. El tiempo máximo es el {@code timeout} de la fase.
 *
 * @param required los checks (nombre del check-run o contexto del status) a los que espera; vacío
 *     para todos
 * @param ifNoChecks qué hace si no aparece ningún check: {@code pass} (lo predeterminado) o {@code
 *     fail}
 * @param fix fase {@code agent} que corrige la CI roja en su sesión; {@code null} si no se corrige
 * @param maxRounds rondas de corrección como mucho
 */
public record CiSpec(List<String> required, String ifNoChecks, String fix, int maxRounds) {

  public static final String PASS = "pass";
  public static final String FAIL = "fail";
  public static final int DEFAULT_MAX_ROUNDS = 2;

  public static final CiSpec DEFAULT = new CiSpec(null, PASS, null, DEFAULT_MAX_ROUNDS);

  public CiSpec {
    required = required == null ? List.of() : List.copyOf(required);
    ifNoChecks = ifNoChecks == null ? PASS : ifNoChecks;
  }
}
