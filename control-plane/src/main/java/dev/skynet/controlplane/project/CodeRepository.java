package dev.skynet.controlplane.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
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
  private final Instant createdAt;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getVersion() {
    return version;
  }
}
