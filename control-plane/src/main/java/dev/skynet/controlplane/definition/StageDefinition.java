package dev.skynet.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.skynet.protocol.AgentFailure;
import dev.skynet.protocol.runner.VerificationCheck;
import java.time.Duration;
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
 * @param approval lo que pide una fase {@code human-approval}; {@code null} en las demás
 * @param retry cuándo se reintenta una fase {@code agent} o {@code command} que falla (W6); {@code
 *     null} si no se reintenta. En una {@code parallel}, cada hijo lleva el suyo
 * @param timeout tiempo máximo de cada intento de la fase, desde que está lista hasta que termina
 *     (W6-B); {@code null} si no tiene. En una {@code human-approval}, cuánto espera la decisión
 * @param phase macrofase de la fase ({@code spec}, {@code plan}, {@code implement}, {@code test},
 *     {@code review} o {@code deliver}), que el grafo dibuja como carril (W7-C); {@code null} si no
 *     la dice
 * @param memory acceso de su agente (o de los hijos de una {@code parallel}) a la memoria del
 *     proyecto (ME-C): {@code off}, {@code read} o {@code write}; {@code null} para {@code write}
 * @param pullRequest la PR que abre una fase {@code github-pr} (EN-A); {@code null} en las demás
 * @param ci cómo espera la CI una fase {@code github-check} (EN-B); {@code null} en las demás
 * @param merge cómo fusiona la PR una fase {@code github-merge} (EN-C); {@code null} en las demás
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
    List<VerificationCheck> checks,
    Approval approval,
    Retry retry,
    @JsonIgnore Duration timeout,
    String phase,
    String memory,
    PullRequestSpec pullRequest,
    CiSpec ci,
    MergeSpec merge) {

  /** Las macrofases que admite {@code phase}, en el orden de los carriles del grafo. */
  public static final List<String> PHASES =
      List.of("spec", "plan", "implement", "test", "review", "deliver");

  /** Sin memoria: ni bloque de contexto ni herramientas. */
  public static final String MEMORY_OFF = "off";

  /** Lee la memoria (contexto y búsqueda), pero no guarda. */
  public static final String MEMORY_READ = "read";

  /** Lee y guarda: lo predeterminado. */
  public static final String MEMORY_WRITE = "write";

  /** Los valores que admite {@code memory}. */
  public static final List<String> MEMORY_MODES = List.of(MEMORY_OFF, MEMORY_READ, MEMORY_WRITE);

  public StageDefinition {
    dependsOn = List.copyOf(dependsOn);
    children = children == null ? List.of() : List.copyOf(children);
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** Una fase que no fusiona ninguna PR. */
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
      String outputSchemaPath,
      List<VerificationCheck> checks,
      Approval approval,
      Retry retry,
      Duration timeout,
      String phase,
      String memory,
      PullRequestSpec pullRequest,
      CiSpec ci) {
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
        checks,
        approval,
        retry,
        timeout,
        phase,
        memory,
        pullRequest,
        ci,
        null);
  }

  /** Una fase que no espera la CI. */
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
      String outputSchemaPath,
      List<VerificationCheck> checks,
      Approval approval,
      Retry retry,
      Duration timeout,
      String phase,
      String memory,
      PullRequestSpec pullRequest) {
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
        checks,
        approval,
        retry,
        timeout,
        phase,
        memory,
        pullRequest,
        null);
  }

  /** Una fase que no abre ninguna PR. */
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
      String outputSchemaPath,
      List<VerificationCheck> checks,
      Approval approval,
      Retry retry,
      Duration timeout,
      String phase,
      String memory) {
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
        checks,
        approval,
        retry,
        timeout,
        phase,
        memory,
        null);
  }

  /** Una fase con la memoria predeterminada ({@code write}). */
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
      String outputSchemaPath,
      List<VerificationCheck> checks,
      Approval approval,
      Retry retry,
      Duration timeout,
      String phase) {
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
        checks,
        approval,
        retry,
        timeout,
        phase,
        null);
  }

  /** Acceso efectivo a la memoria: el de {@code memory} o, si no lo dice, {@code write}. */
  public String memoryOrDefault() {
    return memory == null ? MEMORY_WRITE : memory;
  }

  /** Una fase sin tiempo máximo. */
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
      String outputSchemaPath,
      List<VerificationCheck> checks,
      Approval approval,
      Retry retry) {
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
        checks,
        approval,
        retry,
        null,
        null);
  }

  /** En la API, el {@code timeout} en segundos, o {@code null}. */
  @JsonProperty
  public Long timeoutSeconds() {
    return timeout == null ? null : timeout.toSeconds();
  }

  /** Una fase que no se reintenta. */
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
      String outputSchemaPath,
      List<VerificationCheck> checks,
      Approval approval) {
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
        checks,
        approval,
        (Retry) null);
  }

  /** Una fase que no es una puerta de aprobación. */
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
      String outputSchemaPath,
      List<VerificationCheck> checks) {
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
        checks,
        null);
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
   * Lo que pide una fase {@code human-approval} (W5).
   *
   * @param action la acción que se aprueba, con las variables de un prompt
   * @param risk el riesgo de aprobarla
   * @param revise fase cuyo agente corrige al pedir cambios, o {@code null} si no hay a quién
   */
  public record Approval(String action, Risk risk, String revise) {}

  /** Riesgo de una aprobación ({@code risk} en el YAML). */
  public enum Risk {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String yaml;

    Risk(String yaml) {
      this.yaml = yaml;
    }

    public String yaml() {
      return yaml;
    }
  }

  /**
   * Cuándo se reintenta una fase que falla ({@code retry} en el YAML, W6).
   *
   * @param maxAttempts intentos en total, incluido el primero
   * @param on por qué fallos se reintenta
   * @param delay espera antes del segundo intento; se dobla en cada uno, hasta {@link #MAX_DELAY}
   */
  public record Retry(int maxAttempts, List<AgentFailure> on, Duration delay) {

    /** Lo que se reintenta si {@code on} no dice otra cosa: los fallos pasajeros. */
    public static final List<AgentFailure> DEFAULT_ON =
        List.of(AgentFailure.LOST, AgentFailure.START, AgentFailure.ERROR);

    public static final Duration DEFAULT_DELAY = Duration.ofSeconds(30);
    public static final Duration MAX_DELAY = Duration.ofHours(1);

    public Retry {
      on = List.copyOf(on);
    }

    /** Si tras fallar así el intento {@code attempt} toca otro. */
    public boolean retries(AgentFailure failure, int attempt) {
      return failure != null && on.contains(failure) && attempt < maxAttempts;
    }

    /** La espera antes del intento que sigue al {@code attempt}: 30 s, 60 s, 120 s… */
    public Duration delayAfter(int attempt) {
      Duration wait = delay;
      for (int i = 1; i < attempt && wait.compareTo(MAX_DELAY) < 0; i++) {
        wait = wait.multipliedBy(2);
      }
      return wait.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : wait;
    }

    /** En la API: {@code {maxAttempts, on: [lost, …], delaySeconds}}, como en el YAML. */
    @JsonValue
    public Json json() {
      return new Json(maxAttempts, on.stream().map(AgentFailure::id).toList(), delay.toSeconds());
    }

    public record Json(int maxAttempts, List<String> on, long delaySeconds) {}
  }

  /**
   * Un agente de una fase {@code parallel}.
   *
   * @param id id del hijo, único en la fase; por defecto, el nombre de su agente
   * @param agent agente de {@code agents} que lo ejecuta
   * @param prompt plantilla de su prompt; si es {@code null} se usa la del agente
   * @param retry cuándo se reintenta si falla, o {@code null} si no se reintenta
   */
  public record ChildDefinition(String id, String agent, String prompt, Retry retry) {

    public ChildDefinition(String id, String agent, String prompt) {
      this(id, agent, prompt, null);
    }
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
