package dev.skynet.controlplane.project;

import java.time.Instant;
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
      Instant createdAt,
      Long version) {
    this.id = id;
    this.projectId = projectId;
    this.name = name;
    this.localPath = localPath;
    this.remoteUrl = remoteUrl;
    this.defaultBranch = defaultBranch;
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
        UUID.randomUUID(), projectId, name, localPath, remoteUrl, defaultBranch, now, null);
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getVersion() {
    return version;
  }
}
