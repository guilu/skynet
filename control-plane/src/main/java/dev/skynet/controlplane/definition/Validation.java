package dev.skynet.controlplane.definition;

import java.util.List;

/**
 * Resultado de validar una definición.
 *
 * @param valid sin errores: el borrador está validado
 * @param publishable válida y sin nada que el motor aún no ejecute
 */
public record Validation(boolean valid, boolean publishable, List<Problem> problems) {

  public Validation {
    problems = List.copyOf(problems);
  }

  static Validation of(List<Problem> problems) {
    boolean valid = problems.stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR);
    boolean publishable =
        valid && problems.stream().noneMatch(p -> p.severity() == Problem.Severity.UNSUPPORTED);
    return new Validation(valid, publishable, problems);
  }
}
