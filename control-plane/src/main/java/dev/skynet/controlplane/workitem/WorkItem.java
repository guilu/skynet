package dev.skynet.controlplane.workitem;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("work_item")
public class WorkItem {

  @Id private final UUID id;
  private final UUID projectId;
  private final int number;
  private final String key;
  private String title;
  private String description;
  private WorkItemType type;
  private String externalRef;
  private WorkItemStatus status;
  private final Instant createdAt;
  @Version private Long version;

  @PersistenceCreator
  WorkItem(
      UUID id,
      UUID projectId,
      int number,
      String key,
      String title,
      String description,
      WorkItemType type,
      String externalRef,
      WorkItemStatus status,
      Instant createdAt,
      Long version) {
    this.id = id;
    this.projectId = projectId;
    this.number = number;
    this.key = key;
    this.title = title;
    this.description = description;
    this.type = type;
    this.externalRef = externalRef;
    this.status = status;
    this.createdAt = createdAt;
    this.version = version;
  }

  static WorkItem create(
      UUID projectId,
      String projectKey,
      int number,
      String title,
      String description,
      WorkItemType type,
      String externalRef,
      Instant now) {
    return new WorkItem(
        UUID.randomUUID(),
        projectId,
        number,
        projectKey + "-" + number,
        title,
        description,
        type,
        externalRef,
        WorkItemStatus.OPEN,
        now,
        null);
  }

  public UUID getId() {
    return id;
  }

  public UUID getProjectId() {
    return projectId;
  }

  public int getNumber() {
    return number;
  }

  public String getKey() {
    return key;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public WorkItemType getType() {
    return type;
  }

  public String getExternalRef() {
    return externalRef;
  }

  public WorkItemStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getVersion() {
    return version;
  }
}
