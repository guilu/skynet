package dev.skynet.controlplane.artifact;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.runner.ArtifactStored;
import dev.skynet.protocol.runner.ArtifactUpload;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Guarda, lista y sirve los artefactos que suben los runners. */
@Service
public class ArtifactService {

  /** Lo más que se sirve de una vez. */
  public static final int MAX_CHUNK = 8 * 1024 * 1024;

  private static final Set<String> TEXT_TYPES =
      Set.of("application/json", "application/x-ndjson", "application/xml");
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final BlobStore blobs;
  private final PayloadRedactor redactor;
  private final EventStore events;
  private final ObjectMapper json;
  private final ArtifactProperties properties;
  private final TimeSource time;

  ArtifactService(
      JdbcClient jdbc,
      BlobStore blobs,
      PayloadRedactor redactor,
      EventStore events,
      ObjectMapper json,
      ArtifactProperties properties,
      TimeSource time) {
    this.jdbc = jdbc;
    this.blobs = blobs;
    this.redactor = redactor;
    this.events = events;
    this.json = json;
    this.properties = properties;
    this.time = time;
  }

  /** Metadatos de una subida: el JSON de {@link ArtifactUpload} en base64url. */
  public ArtifactUpload metadata(String header) {
    try {
      return json.readValue(Base64.getUrlDecoder().decode(header.strip()), ArtifactUpload.class);
    } catch (IllegalArgumentException | JacksonException e) {
      throw new InvalidArtifactException("Metadatos del artefacto no válidos: " + e.getMessage());
    }
  }

  /**
   * Contenido de una subida. Se lee como mucho {@code skynet.artifacts.max-upload}: lo que pase se
   * rechaza sin seguir leyendo.
   */
  public byte[] content(InputStream body) throws IOException {
    long max = properties.maxUpload().toBytes();
    byte[] content = body.readNBytes(Math.toIntExact(Math.min(max + 1, Integer.MAX_VALUE - 8)));
    if (content.length > max) {
      throw new ArtifactTooLargeException(
          "El artefacto supera el máximo de " + properties.maxUpload().toMegabytes() + " MB");
    }
    return content;
  }

  /**
   * Guarda un artefacto que sube el runner del agente. Comprueba el sha256 del contenido recibido,
   * redacta los secretos si es de texto y lo recorta al tamaño máximo. Es idempotente: reenviar el
   * mismo artefacto (invocación, verificación, tipo y nombre) devuelve el ya guardado.
   */
  @Transactional
  public ArtifactStored store(UUID runnerId, ArtifactUpload upload, byte[] content) {
    Agent agent =
        agent(upload.agentRunId())
            .orElseThrow(() -> new NotFoundException("Ejecución de agente", upload.agentRunId()));
    if (!runnerId.equals(agent.runnerId())) {
      throw new ConflictException(
          "El agente " + upload.agentRunId() + " no se ejecuta en este runner");
    }
    if (upload.verificationRunId() != null && !verificationOf(upload)) {
      throw new NotFoundException("Verificación", upload.verificationRunId());
    }
    String received = sha256(content);
    if (!received.equalsIgnoreCase(upload.sha256())) {
      throw new InvalidArtifactException(
          "El sha256 del contenido (" + received + ") no coincide con el declarado");
    }
    Optional<UUID> existing = existing(upload);
    if (existing.isPresent()) {
      return new ArtifactStored(existing.get(), true);
    }

    byte[] stored = isText(upload.mediaType()) ? redact(content) : content;
    boolean truncated = stored.length > maxSize();
    if (truncated) {
      stored = truncate(stored, isText(upload.mediaType()));
    }
    String sha256 = sha256(stored);
    String uri;
    try {
      uri = blobs.put(sha256, stored);
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo guardar el artefacto", e);
    }
    Map<String, Object> metadata = new LinkedHashMap<>(upload.metadata());
    if (!sha256.equals(received)) {
      metadata.put("originalSha256", received);
      metadata.put("originalSize", content.length);
    }

    Instant now = time.now();
    UUID id = UUID.randomUUID();
    Optional<UUID> inserted =
        jdbc.sql(
                "INSERT INTO artifact (id, workflow_run_id, stage_run_id, agent_run_id,"
                    + " verification_run_id, type, name, media_type, size, sha256, uri, truncated,"
                    + " metadata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)"
                    + " ON CONFLICT DO NOTHING RETURNING id")
            .params(
                id,
                agent.workflowRunId(),
                agent.stageRunId(),
                upload.agentRunId(),
                upload.verificationRunId(),
                upload.type().name(),
                upload.name(),
                upload.mediaType(),
                stored.length,
                sha256,
                uri,
                truncated,
                json.writeValueAsString(metadata),
                Timestamp.from(now))
            .query(UUID.class)
            .optional();
    if (inserted.isEmpty()) {
      // Otra subida del mismo artefacto llegó a la vez.
      return new ArtifactStored(existing(upload).orElseThrow(), true);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("agentRunId", upload.agentRunId());
    if (upload.verificationRunId() != null) {
      payload.put("verificationRunId", upload.verificationRunId());
    }
    payload.put("type", upload.type().name());
    payload.put("name", upload.name());
    payload.put("size", stored.length);
    payload.put("truncated", truncated);
    events.append(
        EventDraft.of("artifact", id, "artifact.created", agent.workflowRunId(), payload, now));
    return new ArtifactStored(id, false);
  }

  /** Artefactos de un agente (los suyos y los de sus verificaciones), por orden de llegada. */
  @Transactional(readOnly = true)
  public List<ArtifactSummary> ofAgent(UUID agentRunId) {
    if (agent(agentRunId).isEmpty()) {
      throw new NotFoundException("Ejecución de agente", agentRunId);
    }
    return jdbc.sql("SELECT * FROM artifact WHERE agent_run_id = ? ORDER BY created_at, id")
        .param(agentRunId)
        .query(this::summary)
        .list();
  }

  /** Lee hasta {@code limit} bytes del contenido desde {@code offset}. */
  @Transactional(readOnly = true)
  public ArtifactContent content(UUID artifactId, long offset, int limit) {
    record Stored(String uri, String mediaType, long size) {}
    Stored artifact =
        jdbc.sql("SELECT uri, media_type, size FROM artifact WHERE id = ?")
            .param(artifactId)
            .query(
                (rs, row) ->
                    new Stored(rs.getString("uri"), rs.getString("media_type"), rs.getLong("size")))
            .optional()
            .orElseThrow(() -> new NotFoundException("Artefacto", artifactId));
    long from = Math.clamp(offset, 0, artifact.size());
    int length = Math.clamp(limit, 0, MAX_CHUNK);
    try {
      return new ArtifactContent(
          artifact.mediaType(), artifact.size(), from, blobs.read(artifact.uri(), from, length));
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo leer el artefacto " + artifactId, e);
    }
  }

  static boolean isText(String mediaType) {
    String base = mediaType.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
    return base.startsWith("text/") || TEXT_TYPES.contains(base) || base.endsWith("+json");
  }

  private byte[] redact(byte[] content) {
    // Los bytes que no son UTF-8 válido se sustituyen: el texto se muestra igualmente.
    return redactor
        .redact(new String(content, StandardCharsets.UTF_8))
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Recorta al tamaño máximo. En texto no parte un carácter UTF-8 y añade una marca visible con lo
   * que falta.
   */
  private byte[] truncate(byte[] content, boolean text) {
    int max = maxSize();
    if (!text) {
      return Arrays.copyOf(content, max);
    }
    byte[] marker =
        ("\n[… recortado: " + (content.length - max) + " bytes más]\n")
            .getBytes(StandardCharsets.UTF_8);
    int cut = Math.max(0, max - marker.length);
    // Retrocede hasta el inicio de un carácter (los bytes de continuación son 10xxxxxx).
    while (cut > 0 && (content[cut] & 0xC0) == 0x80) {
      cut--;
    }
    byte[] result = Arrays.copyOf(content, cut + marker.length);
    System.arraycopy(marker, 0, result, cut, marker.length);
    return result;
  }

  private int maxSize() {
    return (int) Math.min(properties.maxSize().toBytes(), Integer.MAX_VALUE - 1024L);
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }

  private Optional<UUID> existing(ArtifactUpload upload) {
    return jdbc.sql(
            "SELECT id FROM artifact WHERE agent_run_id = ? AND verification_run_id IS NOT DISTINCT"
                + " FROM ? AND type = ? AND name = ?")
        .params(
            upload.agentRunId(), upload.verificationRunId(), upload.type().name(), upload.name())
        .query(UUID.class)
        .optional();
  }

  private boolean verificationOf(ArtifactUpload upload) {
    return jdbc.sql("SELECT count(*) FROM verification_run WHERE id = ? AND agent_run_id = ?")
            .params(upload.verificationRunId(), upload.agentRunId())
            .query(Integer.class)
            .single()
        > 0;
  }

  private record Agent(UUID runnerId, UUID stageRunId, UUID workflowRunId) {}

  private Optional<Agent> agent(UUID agentRunId) {
    return jdbc.sql(
            "SELECT a.runner_id, a.stage_run_id, s.workflow_run_id FROM agent_run a"
                + " JOIN stage_run s ON s.id = a.stage_run_id WHERE a.id = ?")
        .param(agentRunId)
        .query(
            (rs, row) ->
                new Agent(
                    rs.getObject("runner_id", UUID.class),
                    rs.getObject("stage_run_id", UUID.class),
                    rs.getObject("workflow_run_id", UUID.class)))
        .optional();
  }

  private ArtifactSummary summary(ResultSet rs, int row) throws SQLException {
    return new ArtifactSummary(
        rs.getObject("id", UUID.class),
        rs.getObject("agent_run_id", UUID.class),
        rs.getObject("verification_run_id", UUID.class),
        rs.getString("type"),
        rs.getString("name"),
        rs.getString("media_type"),
        rs.getLong("size"),
        rs.getString("sha256"),
        rs.getBoolean("truncated"),
        json.readValue(rs.getString("metadata"), MAP),
        rs.getTimestamp("created_at").toInstant());
  }
}
