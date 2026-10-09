package dev.skynet.controlplane.purge;

import dev.skynet.controlplane.artifact.BlobStore;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.WorkspaceCleanup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Elimina lo archivado con todo lo que cuelga de ello. Cada eliminación es una transacción: o se
 * borra todo o nada. Antes, {@link #preview} dice qué se borraría y qué lo impide.
 */
@Service
class PurgeService {

  private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

  private final JdbcClient jdbc;
  private final EventStore events;
  private final BlobStore blobs;
  private final WorkspaceCleanup cleanup;
  private final TimeSource time;

  PurgeService(
      JdbcClient jdbc,
      EventStore events,
      BlobStore blobs,
      WorkspaceCleanup cleanup,
      TimeSource time) {
    this.jdbc = jdbc;
    this.events = events;
    this.blobs = blobs;
    this.cleanup = cleanup;
    this.time = time;
  }

  /** Qué se elimina: la raíz (para la lápida) y todo lo que cuelga de ella. */
  enum Kind {
    PROJECT("project", "project.purged"),
    REPOSITORY("repository", "repository.purged"),
    WORK_ITEM("work_item", "workitem.purged"),
    RUN("workflow_run", "workflow.purged"),
    RUNNER("runner", "runner.purged");

    final String aggregateType;
    final String eventType;

    Kind(String aggregateType, String eventType) {
      this.aggregateType = aggregateType;
      this.eventType = eventType;
    }
  }

  /**
   * Lo que cuelga de la raíz.
   *
   * @param identity clave, nombre o título de la raíz, para la lápida
   * @param blockers motivos propios de la raíz (sin archivar, en curso, con historial…)
   */
  private record Scope(
      Kind kind,
      UUID id,
      Map<String, Object> identity,
      List<UUID> runs,
      List<UUID> workItems,
      List<UUID> repositories,
      List<String> blockers) {}

  @Transactional(readOnly = true)
  public DeletionPreview preview(Kind kind, UUID id, UUID parentId) {
    Scope scope = scope(kind, id, parentId, false);
    return preview(scope, agentsOf(scope.runs()));
  }

  /**
   * Elimina la raíz y todo lo que cuelga de ella, y deja un evento lápida. Da 409 con los motivos
   * si algo lo impide.
   */
  @Transactional
  public DeletionPreview.Counts delete(Kind kind, UUID id, UUID parentId) {
    Scope scope = scope(kind, id, parentId, true);
    List<UUID> agents = agentsOf(scope.runs());
    DeletionPreview preview = preview(scope, agents);
    if (!preview.deletable()) {
      throw new ConflictException(String.join(". ", preview.blockers()));
    }
    // Solo esta transacción puede borrar eventos (V11__purge.sql).
    jdbc.sql("SELECT set_config('skynet.purge', 'on', true)").query(String.class).single();

    List<UUID> stages =
        ids("SELECT id FROM stage_run WHERE workflow_run_id IN (:ids)", scope.runs());
    List<UUID> verifications =
        ids("SELECT id FROM verification_run WHERE agent_run_id IN (:ids)", agents);
    List<UUID> artifacts = ids("SELECT id FROM artifact WHERE agent_run_id IN (:ids)", agents);
    List<UUID> workspaces = ownWorkspaces(agents);
    List<String> blobUris =
        agents.isEmpty()
            ? List.of()
            : jdbc.sql("SELECT DISTINCT uri FROM artifact WHERE agent_run_id IN (:ids)")
                .param("ids", agents)
                .query(String.class)
                .list();

    // Las sesiones que continúan en otras ejecuciones pierden el enlace con su origen.
    update(
        "UPDATE agent_run SET parent_agent_run_id = NULL WHERE parent_agent_run_id IN (:ids)"
            + " AND id NOT IN (:ids)",
        agents);

    List<UUID> aggregates = new ArrayList<>();
    aggregates.add(scope.id());
    aggregates.addAll(scope.runs());
    aggregates.addAll(stages);
    aggregates.addAll(agents);
    aggregates.addAll(verifications);
    aggregates.addAll(artifacts);
    aggregates.addAll(workspaces);
    aggregates.addAll(scope.workItems());
    aggregates.addAll(scope.repositories());
    update("DELETE FROM event WHERE workflow_run_id IN (:ids)", scope.runs());
    update("DELETE FROM event WHERE aggregate_id IN (:ids)", aggregates);

    update("DELETE FROM prompt WHERE agent_run_id IN (:ids)", agents);
    update("DELETE FROM artifact WHERE agent_run_id IN (:ids)", agents);
    update("DELETE FROM verification_run WHERE agent_run_id IN (:ids)", agents);
    update("DELETE FROM runner_command WHERE agent_run_id IN (:ids)", agents);
    update("DELETE FROM agent_run WHERE id IN (:ids)", agents);
    update("DELETE FROM workspace WHERE id IN (:ids)", workspaces);
    update("DELETE FROM stage_run WHERE workflow_run_id IN (:ids)", scope.runs());
    update("DELETE FROM workflow_run WHERE id IN (:ids)", scope.runs());
    update("DELETE FROM work_item WHERE id IN (:ids)", scope.workItems());
    update("DELETE FROM workspace WHERE repository_id IN (:ids)", scope.repositories());
    update("DELETE FROM repository WHERE id IN (:ids)", scope.repositories());
    switch (kind) {
      case PROJECT -> update("DELETE FROM project WHERE id IN (:ids)", List.of(id));
      case RUNNER -> update("DELETE FROM runner WHERE id IN (:ids)", List.of(id));
      default -> {}
    }

    Map<String, Object> tombstone = new LinkedHashMap<>(scope.identity());
    tombstone.put("counts", preview.counts());
    events.append(
        EventDraft.of(kind.aggregateType, id, kind.eventType, null, tombstone, time.now()));
    afterCommit(() -> collectBlobs(blobUris));
    return preview.counts();
  }

  /**
   * Pide eliminar los worktrees que impiden eliminar la raíz: los que siguen en su runner y solo
   * usa lo que se eliminaría.
   *
   * @return cuántos se han pedido ahora
   */
  @Transactional
  public int cleanupWorkspaces(Kind kind, UUID id) {
    Scope scope = scope(kind, id, null, false);
    return cleanup.requestAll(liveWorkspaces(agentsOf(scope.runs())));
  }

  private DeletionPreview preview(Scope scope, List<UUID> agents) {
    List<String> blockers = new ArrayList<>(scope.blockers());
    List<String> warnings = new ArrayList<>();
    int live = liveWorkspaces(agents).size();
    if (live > 0) {
      blockers.add(
          live
              + (live == 1 ? " worktree sigue" : " worktrees siguen")
              + " en su runner: elimínalos antes (o espera a que terminen de eliminarse)");
    }
    long continued =
        count(
            "SELECT count(*) FROM agent_run WHERE parent_agent_run_id IN (:ids)"
                + " AND id NOT IN (:ids)",
            agents);
    if (continued > 0) {
      warnings.add(
          continued
              + (continued == 1
                  ? " invocación de otra ejecución continúa"
                  : " invocaciones de" + " otras ejecuciones continúan")
              + " una sesión de aquí: se conserva, pero pierde el enlace con su origen");
    }
    long[] artifacts =
        agents.isEmpty()
            ? new long[] {0, 0}
            : jdbc.sql(
                    "SELECT count(*), coalesce(sum(size), 0) FROM artifact"
                        + " WHERE agent_run_id IN (:ids)")
                .param("ids", agents)
                .query((rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
    long events =
        count("SELECT count(*) FROM event WHERE workflow_run_id IN (:ids)", scope.runs())
            + countEventsOf(scope);
    return new DeletionPreview(
        blockers.isEmpty(),
        blockers,
        warnings,
        live,
        new DeletionPreview.Counts(
            scope.repositories().size(),
            scope.workItems().size(),
            scope.runs().size(),
            agents.size(),
            artifacts[0],
            artifacts[1],
            events));
  }

  /** Eventos sin ejecución que también se borran: los de la raíz, sus trabajos y repositorios. */
  private long countEventsOf(Scope scope) {
    List<UUID> aggregates = new ArrayList<>();
    if (scope.kind() != Kind.RUN) {
      aggregates.add(scope.id());
    }
    aggregates.addAll(scope.workItems());
    aggregates.addAll(scope.repositories());
    return count(
        "SELECT count(*) FROM event WHERE aggregate_id IN (:ids) AND workflow_run_id IS NULL",
        aggregates);
  }

  // --- Qué cuelga de cada raíz ---

  private Scope scope(Kind kind, UUID id, UUID parentId, boolean lock) {
    String forUpdate = lock ? " FOR UPDATE" : "";
    return switch (kind) {
      case RUN -> runScope(id, forUpdate);
      case WORK_ITEM -> workItemScope(id, forUpdate);
      case PROJECT -> projectScope(id, forUpdate);
      case REPOSITORY -> repositoryScope(parentId, id, forUpdate);
      case RUNNER -> runnerScope(id, forUpdate);
    };
  }

  private Scope runScope(UUID id, String forUpdate) {
    record Row(String status, boolean archived, UUID workItemId, String key) {}
    Row row =
        jdbc.sql(
                "SELECT r.status, (r.archived_at IS NOT NULL OR w.archived_at IS NOT NULL"
                    + " OR p.archived_at IS NOT NULL) AS archived, w.id AS work_item_id, w.key"
                    + " FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
                    + " JOIN project p ON p.id = w.project_id WHERE r.id = ?"
                    + (forUpdate.isEmpty() ? "" : " FOR UPDATE OF r"))
            .param(id)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getString("status"),
                        rs.getBoolean("archived"),
                        rs.getObject("work_item_id", UUID.class),
                        rs.getString("key")))
            .optional()
            .orElseThrow(() -> new NotFoundException("Ejecución", id));
    List<String> blockers = new ArrayList<>();
    if (!row.archived()) {
      blockers.add("La ejecución no está archivada: archívala antes de eliminarla");
    }
    if (row.status().equals("PENDING") || row.status().equals("RUNNING")) {
      blockers.add("La ejecución sigue en curso: cancélala o espera a que termine");
    }
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("workItemId", row.workItemId());
    identity.put("workItemKey", row.key());
    return new Scope(Kind.RUN, id, identity, List.of(id), List.of(), List.of(), blockers);
  }

  private Scope workItemScope(UUID id, String forUpdate) {
    record Row(boolean archived, UUID projectId, String key, String title) {}
    Row row =
        jdbc.sql(
                "SELECT (w.archived_at IS NOT NULL OR p.archived_at IS NOT NULL) AS archived,"
                    + " w.project_id, w.key, w.title FROM work_item w"
                    + " JOIN project p ON p.id = w.project_id WHERE w.id = ?"
                    + (forUpdate.isEmpty() ? "" : " FOR UPDATE OF w"))
            .param(id)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getBoolean("archived"),
                        rs.getObject("project_id", UUID.class),
                        rs.getString("key"),
                        rs.getString("title")))
            .optional()
            .orElseThrow(() -> new NotFoundException("Trabajo", id));
    List<String> blockers = new ArrayList<>();
    if (!row.archived()) {
      blockers.add("El trabajo no está archivado: archívalo antes de eliminarlo");
    }
    List<UUID> runs = ids("SELECT id FROM workflow_run WHERE work_item_id IN (:ids)", List.of(id));
    activeRuns(runs, blockers);
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("projectId", row.projectId());
    identity.put("key", row.key());
    identity.put("title", row.title());
    return new Scope(Kind.WORK_ITEM, id, identity, runs, List.of(id), List.of(), blockers);
  }

  private Scope projectScope(UUID id, String forUpdate) {
    record Row(boolean archived, String key, String name) {}
    Row row =
        jdbc.sql(
                "SELECT archived_at IS NOT NULL AS archived, key, name FROM project WHERE id = ?"
                    + forUpdate)
            .param(id)
            .query((rs, n) -> new Row(rs.getBoolean(1), rs.getString(2), rs.getString(3)))
            .optional()
            .orElseThrow(() -> new NotFoundException("Proyecto", id));
    List<String> blockers = new ArrayList<>();
    if (!row.archived()) {
      blockers.add("El proyecto no está archivado: archívalo antes de eliminarlo");
    }
    List<UUID> workItems = ids("SELECT id FROM work_item WHERE project_id IN (:ids)", List.of(id));
    List<UUID> runs = ids("SELECT id FROM workflow_run WHERE work_item_id IN (:ids)", workItems);
    activeRuns(runs, blockers);
    List<UUID> repositories =
        ids("SELECT id FROM repository WHERE project_id IN (:ids)", List.of(id));
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("key", row.key());
    identity.put("name", row.name());
    return new Scope(Kind.PROJECT, id, identity, runs, workItems, repositories, blockers);
  }

  private Scope repositoryScope(UUID projectId, UUID id, String forUpdate) {
    record Row(boolean archived, String name) {}
    Row row =
        jdbc.sql(
                "SELECT (r.archived_at IS NOT NULL OR p.archived_at IS NOT NULL) AS archived,"
                    + " r.name FROM repository r JOIN project p ON p.id = r.project_id"
                    + " WHERE r.id = ? AND r.project_id = ?"
                    + (forUpdate.isEmpty() ? "" : " FOR UPDATE OF r"))
            .params(id, projectId)
            .query((rs, n) -> new Row(rs.getBoolean(1), rs.getString(2)))
            .optional()
            .orElseThrow(() -> new NotFoundException("Repositorio", id));
    List<String> blockers = new ArrayList<>();
    if (!row.archived()) {
      blockers.add("El repositorio no está archivado: archívalo antes de eliminarlo");
    }
    long used =
        count("SELECT count(*) FROM agent_run WHERE repository_id IN (:ids)", List.of(id))
            + count("SELECT count(*) FROM workspace WHERE repository_id IN (:ids)", List.of(id));
    if (used > 0) {
      blockers.add(
          "Hay agentes que trabajaron en este repositorio: se queda archivado para conservar"
              + " sus ejecuciones");
    }
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("projectId", projectId);
    identity.put("name", row.name());
    return new Scope(Kind.REPOSITORY, id, identity, List.of(), List.of(), List.of(id), blockers);
  }

  private Scope runnerScope(UUID id, String forUpdate) {
    record Row(boolean archived, String name) {}
    Row row =
        jdbc.sql("SELECT archived_at IS NOT NULL, name FROM runner WHERE id = ?" + forUpdate)
            .param(id)
            .query((rs, n) -> new Row(rs.getBoolean(1), rs.getString(2)))
            .optional()
            .orElseThrow(() -> new NotFoundException("Runner", id));
    List<String> blockers = new ArrayList<>();
    if (!row.archived()) {
      blockers.add("El runner no está olvidado: olvídalo antes de eliminarlo");
    }
    long used =
        count("SELECT count(*) FROM agent_run WHERE runner_id IN (:ids)", List.of(id))
            + count("SELECT count(*) FROM runner_command WHERE runner_id IN (:ids)", List.of(id))
            + count("SELECT count(*) FROM workspace WHERE runner_id IN (:ids)", List.of(id))
            + count("SELECT count(*) FROM verification_run WHERE runner_id IN (:ids)", List.of(id));
    if (used > 0) {
      blockers.add("El runner ejecutó agentes: se queda olvidado para conservar sus ejecuciones");
    }
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("name", row.name());
    return new Scope(Kind.RUNNER, id, identity, List.of(), List.of(), List.of(), blockers);
  }

  private void activeRuns(List<UUID> runs, List<String> blockers) {
    long active =
        count(
            "SELECT count(*) FROM workflow_run WHERE id IN (:ids)"
                + " AND status IN ('PENDING', 'RUNNING')",
            runs);
    if (active > 0) {
      blockers.add(
          active
              + (active == 1 ? " ejecución sigue" : " ejecuciones siguen")
              + " en curso: cancélalas o espera a que terminen");
    }
  }

  private List<UUID> agentsOf(List<UUID> runs) {
    return ids(
        "SELECT a.id FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
            + " WHERE s.workflow_run_id IN (:ids)",
        runs);
  }

  /** Worktrees de estos agentes que no usa ningún otro: se borran con ellos. */
  private List<UUID> ownWorkspaces(List<UUID> agents) {
    return ids(
        "SELECT DISTINCT w.id FROM workspace w JOIN agent_run a ON a.workspace_id = w.id"
            + " WHERE a.id IN (:ids) AND NOT EXISTS (SELECT 1 FROM agent_run o"
            + " WHERE o.workspace_id = w.id AND o.id NOT IN (:ids))",
        agents);
  }

  /**
   * De esos worktrees, los que siguen en su runner (o se están eliminando). Los de un runner
   * olvidado no cuentan: ya no hay quien los elimine.
   */
  private List<UUID> liveWorkspaces(List<UUID> agents) {
    List<UUID> own = ownWorkspaces(agents);
    return ids(
        "SELECT w.id FROM workspace w JOIN runner r ON r.id = w.runner_id"
            + " WHERE w.id IN (:ids) AND w.removed_at IS NULL AND r.archived_at IS NULL",
        own);
  }

  // --- Ayudas ---

  private List<UUID> ids(String sql, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(sql).param("ids", ids).query(UUID.class).list();
  }

  private long count(String sql, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbc.sql(sql).param("ids", ids).query(Long.class).single();
  }

  private void update(String sql, Collection<UUID> ids) {
    if (!ids.isEmpty()) {
      jdbc.sql(sql).param("ids", ids).update();
    }
  }

  private static void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  /**
   * Borra los blobs que ya no usa ningún artefacto. Va después del commit: si la transacción falla,
   * los blobs siguen ahí. Un fallo aquí solo deja un fichero huérfano, no rompe nada.
   */
  private void collectBlobs(List<String> uris) {
    for (String uri : uris) {
      try {
        long users =
            jdbc.sql("SELECT count(*) FROM artifact WHERE uri = ?")
                .param(uri)
                .query(Long.class)
                .single();
        if (users == 0) {
          blobs.delete(uri);
        }
      } catch (IOException | RuntimeException e) {
        log.warn("No se pudo borrar el blob {}", uri, e);
      }
    }
  }
}
