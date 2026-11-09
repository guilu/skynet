package dev.skynet.controlplane.project;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Repositorio de código de un proyecto. {@code localPath} es la ruta en la máquina del runner; el
 * control plane no accede a ella.
 */
@Table("repository")
public class CodeRepository {

  @Id private final UUID id;
  private final UUID projectId;
  private final String name;
  private String localPath;
  private String remoteUrl;
  private String defaultBranch;
  private String validationCommand;
  private List<String> testReportPaths;
  private boolean agentPolicyCustom;
  private List<String> agentAllowedTools;
  private String agentPermissionMode;
  private List<String> agentEnv;
  private Integer agentMaxTurns;
  private BigDecimal agentMaxBudgetUsd;
  private Integer agentTimeoutMinutes;
  private List<String> protectedPaths;
  private List<String> riskPaths;
  private List<String> riskPatterns;
  private final Instant createdAt;

  /** Lo escribe solo el archivado (UPDATE directo); el resto de guardados no lo toca. */
  @ReadOnlyProperty private Instant archivedAt;

  @Version private Long version;

  @PersistenceCreator
  CodeRepository(
      UUID id,
      UUID projectId,
      String name,
      String localPath,
      String remoteUrl,
      String defaultBranch,
      String validationCommand,
      List<String> testReportPaths,
      boolean agentPolicyCustom,
      List<String> agentAllowedTools,
      String agentPermissionMode,
      List<String> agentEnv,
      Integer agentMaxTurns,
      BigDecimal agentMaxBudgetUsd,
      Integer agentTimeoutMinutes,
      List<String> protectedPaths,
      List<String> riskPaths,
      List<String> riskPatterns,
      Instant createdAt,
      Long version) {
    this.id = id;
    this.projectId = projectId;
    this.name = name;
    this.localPath = localPath;
    this.remoteUrl = remoteUrl;
    this.defaultBranch = defaultBranch;
    this.validationCommand = validationCommand;
    this.testReportPaths = testReportPaths == null ? List.of() : List.copyOf(testReportPaths);
    this.agentPolicyCustom = agentPolicyCustom;
    this.agentAllowedTools = agentAllowedTools == null ? List.of() : List.copyOf(agentAllowedTools);
    this.agentPermissionMode = agentPermissionMode;
    this.agentEnv = agentEnv == null ? null : List.copyOf(agentEnv);
    this.agentMaxTurns = agentMaxTurns;
    this.agentMaxBudgetUsd = agentMaxBudgetUsd;
    this.agentTimeoutMinutes = agentTimeoutMinutes;
    this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
    this.riskPaths = riskPaths == null ? List.of() : List.copyOf(riskPaths);
    this.riskPatterns = riskPatterns == null ? List.of() : List.copyOf(riskPatterns);
    this.createdAt = createdAt;
    this.version = version;
  }

  static CodeRepository create(
      UUID projectId,
      String name,
      String localPath,
      String remoteUrl,
      String defaultBranch,
      Instant now) {
    return new CodeRepository(
        UUID.randomUUID(),
        projectId,
        name,
        localPath,
        remoteUrl,
        defaultBranch,
        null,
        DEFAULT_TEST_REPORT_PATHS,
        false,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        now,
        null);
  }

  /** Informes JUnit de Gradle y de Maven (Surefire). */
  public static final List<String> DEFAULT_TEST_REPORT_PATHS =
      List.of("**/build/test-results/**/*.xml", "**/target/surefire-reports/*.xml");

  /**
   * Comando de validación que el runner ejecuta en el worktree tras cada invocación completada, y
   * dónde deja sus informes JUnit. Sin comando, no hay verificación.
   */
  void configureVerification(String command, List<String> reportPaths) {
    this.validationCommand = command == null || command.isBlank() ? null : command.strip();
    this.testReportPaths =
        reportPaths == null
            ? DEFAULT_TEST_REPORT_PATHS
            : reportPaths.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
  }

  /**
   * Rutas que ningún agente de ninguna ejecución puede cambiar en este repositorio (VA-A): globs
   * relativos a su raíz.
   */
  void configureProtectedPaths(List<String> globs) {
    this.protectedPaths =
        globs == null
            ? List.of()
            : globs.stream().map(String::strip).filter(p -> !p.isEmpty()).distinct().toList();
  }

  /**
   * Lo que sube a nivel 2 un cambio en este repositorio (AU-A): globs relativos a su raíz y
   * expresiones regulares para las líneas añadidas. Se suman a las reglas de siempre.
   */
  void configureRisk(List<String> paths, List<String> patterns) {
    this.riskPaths = clean(paths);
    this.riskPatterns = clean(patterns);
  }

  private static List<String> clean(List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(v -> v != null)
            .map(String::strip)
            .filter(v -> !v.isEmpty())
            .distinct()
            .toList();
  }

  /** Política propia de los agentes; sustituye a la global. */
  void configureAgentPolicy(AgentPolicy policy) {
    this.agentPolicyCustom = true;
    this.agentAllowedTools = policy.allowedTools();
    this.agentPermissionMode = policy.permissionMode();
    this.agentEnv = policy.environment();
    this.agentMaxTurns = policy.maxTurns();
    this.agentMaxBudgetUsd = policy.maxBudgetUsd();
    this.agentTimeoutMinutes = policy.timeoutMinutes();
  }

  /** Vuelve a la política global. */
  void inheritAgentPolicy() {
    this.agentPolicyCustom = false;
    this.agentAllowedTools = List.of();
    this.agentPermissionMode = null;
    this.agentEnv = null;
    this.agentMaxTurns = null;
    this.agentMaxBudgetUsd = null;
    this.agentTimeoutMinutes = null;
  }

  /** Política propia de los agentes, o vacío si el repositorio usa la global. */
  Optional<AgentPolicy> customAgentPolicy() {
    return agentPolicyCustom
        ? Optional.of(
            new AgentPolicy(
                agentAllowedTools,
                agentPermissionMode,
                agentEnv,
                agentMaxTurns,
                agentMaxBudgetUsd,
                agentTimeoutMinutes))
        : Optional.empty();
  }

  public boolean hasCustomAgentPolicy() {
    return agentPolicyCustom;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public String getName() {
    return name;
  }

  public String getLocalPath() {
    return localPath;
  }

  public String getRemoteUrl() {
    return remoteUrl;
  }

  public String getDefaultBranch() {
    return defaultBranch;
  }

  public String getValidationCommand() {
    return validationCommand;
  }

  public List<String> getTestReportPaths() {
    return testReportPaths;
  }

  public List<String> getProtectedPaths() {
    return protectedPaths;
  }

  public List<String> getRiskPaths() {
    return riskPaths;
  }

  public List<String> getRiskPatterns() {
    return riskPatterns;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public Long getVersion() {
    return version;
  }
}
