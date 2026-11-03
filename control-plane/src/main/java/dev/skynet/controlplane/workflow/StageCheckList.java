package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProtectedPaths;
import dev.skynet.protocol.runner.VerificationCheck;
import java.util.ArrayList;
import java.util.List;

/**
 * Las comprobaciones que de verdad se hacen en una fase al ejecutarla: las de su definición más las
 * rutas protegidas del repositorio (VA-A), que se suman al check {@code protected} de cada fase
 * {@code agent} o {@code parallel} (o lo crean si el workflow no protege nada).
 */
final class StageCheckList {

  private StageCheckList() {}

  static List<VerificationCheck> of(StageDefinition definition, CodeRepository repository) {
    List<String> fromRepository = repository == null ? List.of() : repository.getProtectedPaths();
    if (fromRepository.isEmpty() || !protectable(definition)) {
      return definition.checks();
    }
    List<VerificationCheck> checks = new ArrayList<>();
    boolean merged = false;
    for (VerificationCheck check : definition.checks()) {
      if (check.name().equals(VerificationCheck.PROTECTED)) {
        checks.add(
            VerificationCheck.withPaths(
                VerificationCheck.PROTECTED, ProtectedPaths.union(check.paths(), fromRepository)));
        merged = true;
      } else {
        checks.add(check);
      }
    }
    if (!merged) {
      checks.add(VerificationCheck.withPaths(VerificationCheck.PROTECTED, fromRepository));
    }
    return List.copyOf(checks);
  }

  /** Las rutas que el agente de la fase no puede cambiar, para su prompt y sus permisos. */
  static List<String> protectedPaths(StageDefinition definition, CodeRepository repository) {
    if (definition == null) {
      return repository == null ? List.of() : repository.getProtectedPaths();
    }
    return of(definition, repository).stream()
        .filter(c -> c.name().equals(VerificationCheck.PROTECTED))
        .findFirst()
        .map(VerificationCheck::paths)
        .orElse(List.of());
  }

  private static boolean protectable(StageDefinition definition) {
    return definition.type() == StageType.AGENT || definition.type() == StageType.PARALLEL;
  }
}
