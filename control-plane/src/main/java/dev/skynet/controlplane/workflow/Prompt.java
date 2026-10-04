package dev.skynet.controlplane.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/** Prompt enviado a un agente, inmutable y con hash para auditoría. */
@Table("prompt")
public class Prompt implements Persistable<UUID> {

  public static final String ROLE_USER = "user";

  @Id private final UUID id;
  private final UUID agentRunId;
  private final String role;
  private final String content;
  private final String sha256;
  private final Instant createdAt;

  @PersistenceCreator
  Prompt(UUID id, UUID agentRunId, String role, String content, String sha256, Instant createdAt) {
    this.id = id;
    this.agentRunId = agentRunId;
    this.role = role;
    this.content = content;
    this.sha256 = sha256;
    this.createdAt = createdAt;
  }

  static Prompt of(UUID agentRunId, String role, String content, Instant now) {
    return new Prompt(UUID.randomUUID(), agentRunId, role, content, sha256(content), now);
  }

  static String sha256(String content) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Los prompts solo se insertan; nunca se actualizan. */
  @Override
  public boolean isNew() {
    return true;
  }

  @Override
  public UUID getId() {
    return id;
  }

  public UUID getAgentRunId() {
    return agentRunId;
  }

  public String getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public String getSha256() {
    return sha256;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
