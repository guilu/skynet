package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.workflow.Approvals;
import dev.skynet.controlplane.workflow.PullRequests;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workflow.StageRunView;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Las órdenes {@code /skynet} que no lanzan nada (DS-B): {@code corrige}, {@code cancela} y {@code
 * estado}, sobre la ejecución de una PR de Skynet o de una issue. Las llama {@link IssueTriggers},
 * dentro de la transacción de la petición: un {@link InvalidRequestException} la rechaza con su
 * mensaje.
 */
@Service
class TriggerCommands {

  /** Lo más largo que se acepta como texto de una corrección (el de las puertas). */
  static final int MAX_CORRECTION = 10_000;

  /**
   * Lo que ha hecho una orden.
   *
   * @param text el comentario de respuesta en GitHub
   */
  record Outcome(
      String verb, String text, UUID repositoryId, UUID workItemId, UUID runId, String key) {}

  private final JdbcClient jdbc;
  private final RunService runs;
  private final Approvals approvals;
  private final PullRequests pullRequests;
  private final PayloadRedactor redactor;
  private final String publicUrl;

  TriggerCommands(
      JdbcClient jdbc,
      RunService runs,
      Approvals approvals,
      PullRequests pullRequests,
      PayloadRedactor redactor,
      @Value("${skynet.github.pull-requests.public-url:}") String publicUrl) {
    this.jdbc = jdbc;
    this.runs = runs;
    this.approvals = approvals;
    this.pullRequests = pullRequests;
    this.redactor = redactor;
    this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
  }

  /** Si {@code number} es una PR que abrió Skynet en {@code fullName}. */
  boolean isSkynetPullRequest(String fullName, long number) {
    return jdbc.sql(
                "SELECT count(*) FROM github_pull_request WHERE lower(full_name) = lower(?)"
                    + " AND number = ?")
            .params(fullName, number)
            .query(Integer.class)
            .single()
        > 0;
  }

  /**
   * Hace la orden {@code command} de {@code sender} en la issue o la PR {@code number}.
   *
   * @throws InvalidRequestException si no se puede, con el motivo para GitHub
   */
  Outcome run(
      SkynetCommand command, String source, long number, boolean pullRequest, String sender) {
    String verb = command.verb();
    if (SkynetCommand.FORBIDDEN.contains(verb)) {
      throw new InvalidRequestException(
          "desde GitHub no se puede aprobar ni fusionar; hazlo en la web");
    }
    if (!verb.equals(SkynetCommand.CORRECT)
        && !verb.equals(SkynetCommand.CANCEL)
        && !verb.equals(SkynetCommand.STATUS)) {
      throw new InvalidRequestException(
          (verb.isEmpty() ? "falta la orden" : "no conozco la orden `" + verb + "`")
              + ". Admito `corrige <qué cambiar>`, `cancela` y `estado`"
              + (pullRequest ? "" : ", o el id de un workflow con disparador en este repositorio"));
    }
    RunView run = runs.run(runOf(source, number, pullRequest));
    UUID repositoryId = repositoryOf(run.id());
    String text =
        switch (verb) {
          case SkynetCommand.CORRECT -> correct(run, command.text(), sender);
          case SkynetCommand.CANCEL -> cancel(run);
          default -> status(run);
        };
    return new Outcome(verb, text, repositoryId, run.workItemId(), run.id(), run.workItemKey());
  }

  private UUID runOf(String source, long number, boolean pullRequest) {
    if (pullRequest) {
      return jdbc.sql(
              "SELECT workflow_run_id FROM github_pull_request WHERE lower(full_name) = lower(?)"
                  + " AND number = ? ORDER BY created_at DESC LIMIT 1")
          .params(source, number)
          .query(UUID.class)
          .optional()
          .orElseThrow(
              () -> new InvalidRequestException("esta PR no es de ninguna ejecución de Skynet"));
    }
    return jdbc.sql(
            "SELECT r.id FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
                + " WHERE w.external_ref = ? ORDER BY r.created_at DESC LIMIT 1")
        .param("github:" + source + "#" + number)
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () -> new InvalidRequestException("esta issue no tiene ninguna ejecución de Skynet"));
  }

  private UUID repositoryOf(UUID runId) {
    return jdbc.sql("SELECT repository_id FROM workflow_run WHERE id = ?")
        .param(runId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private String correct(RunView run, String text, String sender) {
    String comment = text == null ? "" : text.strip();
    if (comment.isEmpty()) {
      throw new InvalidRequestException("falta qué corregir: `/skynet corrige <qué cambiar>`");
    }
    if (comment.length() > MAX_CORRECTION) {
      throw new InvalidRequestException(
          "la corrección ocupa más de " + MAX_CORRECTION + " caracteres");
    }
    UUID approval =
        jdbc.sql(
                "SELECT id FROM approval_request WHERE workflow_run_id = ? AND status = 'PENDING'"
                    + " ORDER BY created_at DESC LIMIT 1")
            .param(run.id())
            .query(UUID.class)
            .optional()
            .orElseThrow(
                () ->
                    new InvalidRequestException(
                        "ahora no se puede corregir: la ejecución de "
                            + run.workItemKey()
                            + " no está esperando en una puerta"));
    approvals.decide(
        approval, Approvals.Action.REQUEST_CHANGES, redactor.redact(comment), "github:" + sender);
    return "Pido los cambios en **"
        + run.workItemKey()
        + "**: el agente lo corrige y la puerta vuelve a preguntar en la web."
        + runLink(run.id());
  }

  private String cancel(RunView run) {
    if (run.status().isTerminal()) {
      throw new InvalidRequestException(
          "la ejecución de "
              + run.workItemKey()
              + " ya ha terminado ("
              + status(run.status())
              + ")");
    }
    runs.cancelRun(run.id());
    return "Cancelando **"
        + run.workItemKey()
        + "**: lo que no ha empezado se cancela y los agentes en marcha terminan."
        + runLink(run.id());
  }

  private String status(RunView run) {
    StringBuilder text = new StringBuilder();
    text.append("**").append(run.workItemKey()).append("**");
    if (run.workflow() != null) {
      text.append(" con *")
          .append(run.workflow().name() == null ? run.workflow().key() : run.workflow().name())
          .append("*");
    }
    text.append(": ").append(status(run.status()));
    if (run.cancelRequestedAt() != null) {
      text.append(" (cancelando)");
    }
    text.append(".");
    // La última vez de cada fase, en el orden en que empezaron.
    Map<String, StageRunView> stages = new LinkedHashMap<>();
    for (StageRunView stage : run.stages()) {
      stages.put(stage.stageKey(), stage);
    }
    if (!stages.isEmpty()) {
      text.append("\n");
      for (StageRunView stage : stages.values()) {
        text.append("\n- ")
            .append(stage.name() == null ? stage.stageKey() : stage.name())
            .append(": ")
            .append(status(stage.status()));
      }
    }
    stages.values().stream()
        .filter(s -> s.status() == StageStatus.WAITING_FOR_APPROVAL)
        .findFirst()
        .ifPresent(
            s ->
                text.append("\n\nEspera tu aprobación en la web: **")
                    .append(s.name() == null ? s.stageKey() : s.name())
                    .append("**."));
    for (PullRequests.View pr : pullRequests.ofRun(run.id())) {
      if (pr.url() != null) {
        text.append("\n\nPR: ").append(pr.url());
      }
    }
    String link = runLink(run.id());
    if (!link.isEmpty()) {
      text.append("\n\n").append(link.strip());
    }
    return text.toString();
  }

  private String runLink(UUID runId) {
    return publicUrl.isEmpty() ? "" : " [Ver la ejecución](" + publicUrl + "/runs/" + runId + ")";
  }

  static String status(WorkflowRunStatus status) {
    return switch (status) {
      case PENDING -> "en cola";
      case RUNNING -> "en marcha";
      case SUCCEEDED -> "terminada bien";
      case FAILED -> "fallida";
      case CANCELLED -> "cancelada";
    };
  }

  static String status(StageStatus status) {
    return switch (status) {
      case PENDING -> "pendiente";
      case READY, STARTING -> "arrancando";
      case RUNNING -> "en marcha";
      case WAITING_FOR_INPUT -> "esperando una respuesta";
      case WAITING_FOR_APPROVAL -> "esperando aprobación";
      case SUCCEEDED -> "hecha";
      case FAILED -> "fallida";
      case CANCELLED -> "cancelada";
      case SKIPPED -> "saltada";
    };
  }
}
