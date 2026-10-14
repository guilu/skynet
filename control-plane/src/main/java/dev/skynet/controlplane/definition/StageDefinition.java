package dev.skynet.controlplane.definition;

import dev.skynet.protocol.runner.VerificationCheck;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Una fase del workflow.
 *
 * @param agent agente con nombre que la ejecuta, o {@code null} para uno con la política del
 *     repositorio
 * @param prompt plantilla del prompt de la fase; si es {@code null} se usa la del agente
 * @param dependsOn fases que tienen que terminar antes, en el orden del YAML
 * @param workspace de dónde sale su worktree
 * @param workspaceFrom fase cuyo worktree continúa, cuando depende de varias con worktree, o {@code
 *     <fase>.<hijo>} para continuar el de un hijo de una fase {@code parallel}
 * @param command comando de shell de una fase {@code command}; {@code null} en las demás
 * @param children agentes de una fase {@code parallel}, que se ejecutan a la vez; vacío en las
 *     demás
 * @param condition condición que se evalúa cuando la fase está lista: si es falsa, la fase se
 *     omite; {@code null} si no tiene
 * @param outputSchema JSON Schema en línea de la salida estructurada de una fase {@code agent}, que
 *     manda sobre el {@code output} de su agente; {@code null} si no tiene
 * @param outputSchemaPath el mismo schema como ruta de un fichero del repositorio, que el runner
 *     lee del worktree al arrancar; {@code null} si no tiene
 * @param checks lo que Skynet comprueba en su worktree antes de darla por buena ({@code artifacts},
 *     {@code checks} y {@code scope} del YAML), en el orden en que se ejecuta; vacío si nada
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
    String command,
    List<ChildDefinition> children,
    String condition,
    Map<String, Object> outputSchema,
    String outputSchemaPath,
    List<VerificationCheck> checks) {

  public StageDefinition {
    dependsOn = List.copyOf(dependsOn);
    children = children == null ? List.of() : List.copyOf(children);
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** Una fase sin comprobaciones. */
  public StageDefinition(
      String id,
      String name,
      StageType type,
      String agent,
      String prompt,
      List<Dependency> dependsOn,
      WorkspaceMode workspace,
      String workspaceFrom,
      String command,
      List<ChildDefinition> children,
      String condition,
      Map<String, Object> outputSchema,
      String outputSchemaPath) {
    this(
        id,
        name,
        type,
        agent,
        prompt,
        dependsOn,
        workspace,
        workspaceFrom,
        command,
        children,
        condition,
        outputSchema,
        outputSchemaPath,
        List.of());
  }

  /** Si Skynet comprueba su worktree antes de darla por buena. */
  public boolean verified() {
    return !checks.isEmpty();
  }

  /** Si entre sus comprobaciones está {@code tests}: el comando de validación del repositorio. */
  public boolean runsTests() {
    return checks.stream().anyMatch(c -> c.name().equals(VerificationCheck.TESTS));
  }

  /** Una fase sin {@code outputSchema} propio. */
  public StageDefinition(
      String id,
      String name,
      StageType type,
      String agent,
      String prompt,
      List<Dependency> dependsOn,
      WorkspaceMode workspace,
      String workspaceFrom,
      String command,
      List<ChildDefinition> children,
      String condition) {
    this(
        id,
        name,
        type,
        agent,
        prompt,
        dependsOn,
        workspace,
        workspaceFrom,
        command,
        children,
        condition,
        null,
        null);
  }

  /**
   * El schema de la salida de la fase como lo recibe el runner: el JSON en línea o, con una ruta,
   * {@code {"$ref": "<ruta>"}}, que el runner resuelve en el worktree; {@code null} si la fase no
   * tiene uno propio.
   */
  public String outputSchemaJson() {
    if (outputSchemaPath != null) {
      return JsonMapper.shared().writeValueAsString(Map.of("$ref", outputSchemaPath));
    }
    return outputSchema == null ? null : JsonMapper.shared().writeValueAsString(outputSchema);
  }

  /** Una fase sin hijos. */
  public StageDefinition(
      String id,
      String name,
      StageType type,
      String agent,
      String prompt,
      List<Dependency> dependsOn,
      WorkspaceMode workspace,
      String workspaceFrom,
      String command) {
    this(
        id,
        name,
        type,
        agent,
        prompt,
        dependsOn,
        workspace,
        workspaceFrom,
        command,
        List.of(),
        null,
        null,
        null);
  }

  /** El hijo {@code id}, si la fase lo tiene. */
  public Optional<ChildDefinition> child(String id) {
    return children.stream().filter(c -> c.id().equals(id)).findFirst();
  }

  /**
   * Un agente de una fase {@code parallel}.
   *
   * @param id id del hijo, único en la fase; por defecto, el nombre de su agente
   * @param agent agente de {@code agents} que lo ejecuta
   * @param prompt plantilla de su prompt; si es {@code null} se usa la del agente
   */
  public record ChildDefinition(String id, String agent, String prompt) {}

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

  /**
   * De dónde sale el worktree de una fase con agente o comando; en una {@code parallel}, el
   * worktree del que cada hijo recibe una copia.
   */
  public enum WorkspaceMode {
    /** Continúa el de la fase de la que depende (con worktree); sin ninguna, uno nuevo. */
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
