package dev.skynet.controlplane.definition;

import java.util.List;

/**
 * Una fase del workflow.
 *
 * @param agent agente con nombre que la ejecuta, o {@code null} para uno con la política del
 *     repositorio
 * @param prompt plantilla del prompt de la fase; si es {@code null} se usa la del agente
 * @param dependsOn fases que tienen que terminar antes, en el orden del YAML
 * @param workspace de dónde sale su worktree
 * @param workspaceFrom fase cuyo worktree continúa, cuando depende de varias con worktree
 * @param command comando de shell de una fase {@code command}; {@code null} en las demás
 */
public record StageDefinition(
    String id,
    String name,
    StageType type,
    String agent,
    String prompt,
    List<Dependency> dependsOn,
    WorkspaceMode workspace,
    String workspaceFrom,
    String command) {

  public StageDefinition {
    dependsOn = List.copyOf(dependsOn);
  }

  /**
   * Dependencia de otra fase. Una opcional ({@code plan?} en el YAML) también se da por cumplida si
   * esa fase se omite.
   */
  public record Dependency(String stage, boolean optional) {

    @Override
    public String toString() {
      return optional ? stage + "?" : stage;
    }
  }

  /** De dónde sale el worktree de una fase con agente o comando. */
  public enum WorkspaceMode {
    /** Continúa el de la fase de la que depende (agente o comando); sin ninguna, uno nuevo. */
    INHERIT("inherit"),
    /** Siempre uno nuevo desde la rama por defecto. */
    ISOLATED("isolated-worktree");

    private final String yaml;

    WorkspaceMode(String yaml) {
      this.yaml = yaml;
    }

    public String yaml() {
      return yaml;
    }
  }
}
