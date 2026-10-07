package dev.skynet.runner.journal;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.ArtifactUpload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Estado local del runner en SQLite: credenciales, eventos pendientes de enviar, órdenes ya
 * recibidas y procesos vivos. Sobrevive a reinicios, de modo que ningún evento se pierde si se
 * corta la conexión o se reinicia el runner.
 *
 * <p>Una sola conexión, con acceso sincronizado: el volumen es pequeño y así cada operación es
 * atómica sin más coordinación.
 */
public final class Journal implements AutoCloseable {

  // BigDecimal conserva el coste exacto al releer los eventos.
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final Connection db;

  private Journal(Connection db) {
    this.db = db;
  }

  public static Journal open(Path file) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Connection db;
    try {
      db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    } catch (SQLException e) {
      throw new IOException("No se pudo abrir el journal " + file, e);
    }
    try {
      try (Statement st = db.createStatement()) {
        st.execute("PRAGMA journal_mode=WAL");
        st.execute(
            "CREATE TABLE IF NOT EXISTS credential (id INTEGER PRIMARY KEY CHECK (id = 1),"
                + " control_plane TEXT NOT NULL, runner_id TEXT NOT NULL, token TEXT NOT NULL)");
        st.execute(
            "CREATE TABLE IF NOT EXISTS event (event_id TEXT PRIMARY KEY, agent_run_id TEXT NOT"
                + " NULL, seq INTEGER NOT NULL, json TEXT NOT NULL)");
        st.execute(
            "CREATE TABLE IF NOT EXISTS sequence (agent_run_id TEXT PRIMARY KEY, last_seq INTEGER"
                + " NOT NULL)");
        st.execute(
            "CREATE TABLE IF NOT EXISTS command (id TEXT PRIMARY KEY, agent_run_id TEXT NOT NULL,"
                + " type TEXT NOT NULL, received_at TEXT NOT NULL)");
        // Invocaciones cuyo fin (agent.process.exited) ya está en el journal.
        st.execute("CREATE TABLE IF NOT EXISTS finished (agent_run_id TEXT PRIMARY KEY)");
        st.execute(
            "CREATE TABLE IF NOT EXISTS process (agent_run_id TEXT PRIMARY KEY, pid INTEGER NOT"
                + " NULL, started_at TEXT NOT NULL)");
        // Artefactos pendientes de subir: metadatos y fichero con el contenido.
        st.execute(
            "CREATE TABLE IF NOT EXISTS artifact (id TEXT PRIMARY KEY, json TEXT NOT NULL,"
                + " file TEXT NOT NULL)");
        // Verificaciones recibidas; finished = 1 cuando su evento de fin ya está en el journal.
        st.execute(
            "CREATE TABLE IF NOT EXISTS verification (id TEXT PRIMARY KEY, agent_run_id TEXT NOT"
                + " NULL, finished INTEGER NOT NULL DEFAULT 0)");
      }
      return new Journal(db);
    } catch (SQLException e) {
      try {
        db.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw new IOException("No se pudo abrir el journal " + file, e);
    }
  }

  // --- credenciales ---

  public record Credentials(String controlPlane, UUID runnerId, String token) {}

  public synchronized Optional<Credentials> credentials() {
    return query(
            "SELECT control_plane, runner_id, token FROM credential WHERE id = 1",
            ps -> {},
            rs ->
                new Credentials(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3)))
        .stream()
        .findFirst();
  }

  public synchronized void saveCredentials(Credentials credentials) {
    update(
        "INSERT OR REPLACE INTO credential (id, control_plane, runner_id, token)"
            + " VALUES (1, ?, ?, ?)",
        ps -> {
          ps.setString(1, credentials.controlPlane());
          ps.setString(2, credentials.runnerId().toString());
          ps.setString(3, credentials.token());
        });
  }

  // --- eventos ---

  /**
   * Guarda un evento con el siguiente {@code seq} de la invocación, de forma atómica, y lo
   * devuelve. Queda pendiente hasta que el control plane lo confirme.
   */
  public synchronized NormalizedEvent append(
      UUID agentRunId, AgentEventType type, Map<String, Object> payload, Instant occurredAt) {
    return inTransaction(
        () -> {
          long seq =
              query(
                          "SELECT last_seq FROM sequence WHERE agent_run_id = ?",
                          ps -> ps.setString(1, agentRunId.toString()),
                          rs -> rs.getLong(1))
                      .stream()
                      .findFirst()
                      .orElse(0L)
                  + 1;
          update(
              "INSERT OR REPLACE INTO sequence (agent_run_id, last_seq) VALUES (?, ?)",
              ps -> {
                ps.setString(1, agentRunId.toString());
                ps.setLong(2, seq);
              });
          NormalizedEvent event =
              new NormalizedEvent(UUID.randomUUID(), agentRunId, seq, occurredAt, type, payload);
          update(
              "INSERT INTO event (event_id, agent_run_id, seq, json) VALUES (?, ?, ?, ?)",
              ps -> {
                ps.setString(1, event.eventId().toString());
                ps.setString(2, agentRunId.toString());
                ps.setLong(3, seq);
                ps.setString(4, JSON.writeValueAsString(event));
              });
          return event;
        });
  }

  /** Eventos pendientes, en el orden en que se generaron. */
  public synchronized List<NormalizedEvent> pending(int limit) {
    return query(
        "SELECT json FROM event ORDER BY rowid LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs -> JSON.readValue(rs.getString(1), NormalizedEvent.class));
  }

  /**
   * Agentes con eventos aún sin confirmar por el control plane. El latido los declara junto a las
   * invocaciones en curso: una invocación que acaba de terminar no debe darse por perdida mientras
   * su evento de fin sigue en el journal.
   */
  public synchronized List<UUID> agentsWithPendingEvents() {
    return query(
        "SELECT DISTINCT agent_run_id FROM event",
        ps -> {},
        rs -> UUID.fromString(rs.getString(1)));
  }

  /**
   * Olvida los eventos que el control plane ya ha procesado (aceptados, duplicados o rechazados).
   */
  public synchronized void delivered(Collection<UUID> eventIds) {
    inTransaction(
        () -> {
          for (UUID id : eventIds) {
            update("DELETE FROM event WHERE event_id = ?", ps -> ps.setString(1, id.toString()));
          }
          return null;
        });
  }

  // --- órdenes ---

  /**
   * Registra una orden recibida. Devuelve {@code false} si ya se había recibido: el control plane
   * la reentrega si no llegó el {@code ack}, y no debe ejecutarse dos veces.
   */
  public synchronized boolean firstDelivery(UUID commandId, UUID agentRunId, String type) {
    return update(
            "INSERT OR IGNORE INTO command (id, agent_run_id, type, received_at) VALUES (?, ?, ?,"
                + " ?)",
            ps -> {
              ps.setString(1, commandId.toString());
              ps.setString(2, agentRunId.toString());
              ps.setString(3, type);
              ps.setString(4, Instant.now().toString());
            })
        == 1;
  }

  /**
   * Invocaciones que recibieron su orden de arranque y aún no tienen evento de fin: tras un
   * reinicio, son las que el runner dejó a medias.
   */
  public synchronized List<UUID> unfinishedStarts() {
    return query(
        "SELECT agent_run_id FROM command WHERE type IN ('START', 'RESUME')"
            + " AND agent_run_id NOT IN (SELECT agent_run_id FROM finished) ORDER BY received_at",
        ps -> {},
        rs -> UUID.fromString(rs.getString(1)));
  }

  /** Registra el evento de fin de una invocación y la marca como terminada, en un solo paso. */
  public synchronized NormalizedEvent finish(
      UUID agentRunId, Map<String, Object> payload, Instant occurredAt) {
    return inTransaction(
        () -> {
          NormalizedEvent event =
              append(agentRunId, AgentEventType.PROCESS_EXITED, payload, occurredAt);
          update(
              "INSERT OR IGNORE INTO finished (agent_run_id) VALUES (?)",
              ps -> ps.setString(1, agentRunId.toString()));
          update(
              "DELETE FROM process WHERE agent_run_id = ?",
              ps -> ps.setString(1, agentRunId.toString()));
          return event;
        });
  }

  public synchronized boolean isFinished(UUID agentRunId) {
    return !query(
            "SELECT 1 FROM finished WHERE agent_run_id = ?",
            ps -> ps.setString(1, agentRunId.toString()),
            rs -> 1)
        .isEmpty();
  }

  // --- artefactos ---

  /** Artefacto pendiente de subir. */
  public record PendingArtifact(UUID id, ArtifactUpload upload, Path file) {}

  /** Encola un artefacto cuyo contenido ya está en {@code file}. */
  public synchronized void queueArtifact(UUID id, ArtifactUpload upload, Path file) {
    update(
        "INSERT OR REPLACE INTO artifact (id, json, file) VALUES (?, ?, ?)",
        ps -> {
          ps.setString(1, id.toString());
          ps.setString(2, JSON.writeValueAsString(upload));
          ps.setString(3, file.toString());
        });
  }

  /** Artefactos pendientes, en el orden en que se encolaron. */
  public synchronized List<PendingArtifact> pendingArtifacts(int limit) {
    return query(
        "SELECT id, json, file FROM artifact ORDER BY rowid LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs ->
            new PendingArtifact(
                UUID.fromString(rs.getString(1)),
                JSON.readValue(rs.getString(2), ArtifactUpload.class),
                Path.of(rs.getString(3))));
  }

  /** Olvida un artefacto que el control plane ya tiene (o rechazó). */
  public synchronized void artifactDelivered(UUID id) {
    update("DELETE FROM artifact WHERE id = ?", ps -> ps.setString(1, id.toString()));
  }

  // --- verificaciones ---

  /** Verificación recibida y aún sin evento de fin. */
  public record PendingVerification(UUID verificationRunId, UUID agentRunId) {}

  /**
   * Registra una verificación. Devuelve {@code false} si ya se había recibido: no se ejecuta dos
   * veces.
   */
  public synchronized boolean firstVerification(UUID verificationRunId, UUID agentRunId) {
    return update(
            "INSERT OR IGNORE INTO verification (id, agent_run_id) VALUES (?, ?)",
            ps -> {
              ps.setString(1, verificationRunId.toString());
              ps.setString(2, agentRunId.toString());
            })
        == 1;
  }

  /** Registra el evento de fin de una verificación y la marca como terminada, en un solo paso. */
  public synchronized NormalizedEvent finishVerification(
      UUID verificationRunId, UUID agentRunId, Map<String, Object> payload, Instant occurredAt) {
    return inTransaction(
        () -> {
          NormalizedEvent event =
              append(agentRunId, AgentEventType.VERIFICATION_COMPLETED, payload, occurredAt);
          update(
              "UPDATE verification SET finished = 1 WHERE id = ?",
              ps -> ps.setString(1, verificationRunId.toString()));
          update(
              "DELETE FROM process WHERE agent_run_id = ?",
              ps -> ps.setString(1, verificationRunId.toString()));
          return event;
        });
  }

  /** Verificaciones que el runner dejó a medias en una ejecución anterior. */
  public synchronized List<PendingVerification> unfinishedVerifications() {
    return query(
        "SELECT id, agent_run_id FROM verification WHERE finished = 0 ORDER BY rowid",
        ps -> {},
        rs ->
            new PendingVerification(
                UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))));
  }

  // --- procesos ---

  public record TrackedProcess(UUID agentRunId, long pid, Instant startedAt) {}

  /**
   * Proceso vivo de una invocación o de una verificación (por su id), para matarlo tras un
   * reinicio.
   */
  public synchronized void processStarted(UUID agentRunId, long pid, Instant startedAt) {
    update(
        "INSERT OR REPLACE INTO process (agent_run_id, pid, started_at) VALUES (?, ?, ?)",
        ps -> {
          ps.setString(1, agentRunId.toString());
          ps.setLong(2, pid);
          ps.setString(3, startedAt.toString());
        });
  }

  public synchronized List<TrackedProcess> processes() {
    return query(
        "SELECT agent_run_id, pid, started_at FROM process",
        ps -> {},
        rs ->
            new TrackedProcess(
                UUID.fromString(rs.getString(1)), rs.getLong(2), Instant.parse(rs.getString(3))));
  }

  @Override
  public synchronized void close() {
    try {
      db.close();
    } catch (SQLException e) {
      // Nada que hacer al cerrar.
    }
  }

  // --- JDBC ---

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  @FunctionalInterface
  private interface Mapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run();
  }

  private <T> List<T> query(String sql, Binder binder, Mapper<T> mapper) {
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(mapper.map(rs));
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new JournalException(e);
    }
  }

  private int update(String sql, Binder binder) {
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      binder.bind(ps);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new JournalException(e);
    }
  }

  private <T> T inTransaction(Work<T> work) {
    try {
      if (!db.getAutoCommit()) {
        return work.run(); // Ya dentro de una transacción.
      }
      db.setAutoCommit(false);
      try {
        T result = work.run();
        db.commit();
        return result;
      } catch (RuntimeException e) {
        db.rollback();
        throw e;
      } finally {
        db.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new JournalException(e);
    }
  }

  /** Error de SQLite; el journal está en un disco local, así que no se espera recuperarlo. */
  public static final class JournalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    JournalException(SQLException cause) {
      super(cause);
    }
  }
}
