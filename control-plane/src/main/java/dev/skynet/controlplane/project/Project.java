package dev.skynet.controlplane.project;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** Producto o conjunto de repositorios sobre el que se ejecutan trabajos (§5.1). */
@Table("project")
public class Project {

  @Id private final UUID id;
  private final String key;
  private String name;
  private String description;
  private final Instant createdAt;
  @Version private Long version;

  @PersistenceCreator
  Project(UUID id, String key, String name, String description, Instant createdAt, Long version) {
    this.id = id;
    this.key = key;
    this.name = name;
    this.description = description;
    this.createdAt = createdAt;
    this.version = version;
  }

  static Project create(String key, String name, String description, Instant now) {
    return new Project(UUID.randomUUID(), key, name, description, now, null);
  }

  public UUID getId() {
    return id;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getVersion() {
    return version;
  }
}
