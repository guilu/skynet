package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.github.GitHubComment;
import dev.skynet.controlplane.github.GitHubIssue;
import dev.skynet.controlplane.github.IssueHandler;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workflow.WorkflowDefinitionView;
import dev.skynet.controlplane.workflow.WorkflowRun;
import dev.skynet.controlplane.workitem.WorkItemService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Lanzar un workflow con una etiqueta en una issue de GitHub (DS-A).
 *
 * <p>Al llegar la entrega solo se apunta la petición ({@link #handle}), dentro de su transacción.
 * Después, {@link #poll} decide cada una: comprueba el interruptor y quién la pide, busca o crea el
 * trabajo de la issue y lanza el workflow. Lo que haya que contar en GitHub queda como aviso
 * pendiente ({@link TriggerNotices}): si GitHub falla, se reintenta el aviso, nunca el lanzamiento.
 */
@Service
class IssueTriggers implements IssueHandler {

  private static final Logger log = LoggerFactory.getLogger(IssueTriggers.class);

  static final String AGGREGATE = "repository";

  /** Lo más largo que se guarda del título y del cuerpo de una issue. */
  static final int MAX_TITLE = 300;

  static final int MAX_BODY = 20_000;

  /** Intentos ante un error inesperado antes de darla por rechazada. */
  static final int MAX_ATTEMPTS = 5;

  static final Duration RETRY_DELAY = Duration.ofSeconds(5);

  enum Mode {
    ISSUE,
    ORDER,
    /** Una orden {@code /skynet} que no lanza nada: corrige, cancela o estado (DS-B). */
    COMMAND
  }

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final TransactionTemplate transactions;
  private final TriggerSettings settings;
  private final TriggerRules rules;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final RunService runs;
  private final EventStore events;
  private final PayloadRedactor redactor;
  private final TriggerCommands commands;
  private final String publicUrl;

  IssueTriggers(
      JdbcClient jdbc,
      ObjectMapper json,
      TimeSource time,
      TransactionTemplate transactions,
      TriggerSettings settings,
      TriggerRules rules,
      ProjectService projects,
      WorkItemService workItems,
      RunService runs,
      EventStore events,
      PayloadRedactor redactor,
      TriggerCommands commands,
      @Value("${skynet.github.pull-requests.public-url:}") String publicUrl) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.transactions = transactions;
    this.settings = settings;
    this.rules = rules;
    this.projects = projects;
    this.workItems = workItems;
    this.runs = runs;
    this.events = events;
    this.redactor = redactor;
    this.commands = commands;
    this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
  }

  /**
   * Una etiqueta nueva en una issue: si es {@code skynet:run} en un repositorio de órdenes, o la de
   * una regla activa de un repositorio de Skynet enlazado, se apunta la petición.
   */
  @Override
  public boolean handle(GitHubIssue issue) {
    String label = issue.label().strip().toLowerCase(Locale.ROOT);
    if (!label.startsWith(TriggerRules.PREFIX)) {
      return false;
    }
    if (settings.isOrderRepository(issue.fullName())) {
      if (!label.equals(TriggerRules.ORDER_LABEL)) {
        return false;
      }
      queue(Request.of(issue, label, Mode.ORDER, null, null));
      return true;
    }
    if (TriggerRules.RESERVED.contains(label)) {
      return false;
    }
    List<TriggerRules.Rule> matching = rules.forLabel(issue.repositoryIds(), label);
    for (TriggerRules.Rule rule : matching) {
      queue(Request.of(issue, label, Mode.ISSUE, rule.id(), rule.repositoryId()));
    }
    return !matching.isEmpty();
  }

  /**
   * Un comentario {@code /skynet}, o una revisión que pide cambios en una PR de Skynet (DS-B):
   *
   * <ul>
   *   <li>{@code /skynet run} en una issue de un repositorio de órdenes la lanza como con la
   *       etiqueta;
   *   <li>{@code /skynet <workflow> [clave=valor…]} en una issue lanza ese workflow si el
   *       repositorio tiene un disparador activo de él;
   *   <li>lo demás ({@code corrige}, {@code cancela}, {@code estado}…) es una orden sobre la
   *       ejecución de la PR o de la issue.
   * </ul>
   *
   * Una revisión que pide cambios es {@code /skynet corrige} con su texto.
   */
  @Override
  public boolean handleComment(GitHubComment comment) {
    UUID first = comment.repositoryIds().isEmpty() ? null : comment.repositoryIds().getFirst();
    if (comment.kind() == GitHubComment.Kind.CHANGES_REQUESTED) {
      if (!commands.isSkynetPullRequest(comment.fullName(), comment.number())) {
        return false;
      }
      String text = comment.text() == null ? "" : comment.text().strip();
      if (text.isEmpty()) {
        text =
            "Corrige lo que pide la revisión de GitHub"
                + (comment.issueUrl() == null ? "." : ": " + comment.issueUrl());
      }
      SkynetCommand command = new SkynetCommand(SkynetCommand.CORRECT, "", text);
      queue(Request.of(comment, command, Mode.COMMAND, null, first, text));
      return true;
    }
    SkynetCommand command = SkynetCommand.parse(comment.text());
    if (command == null) {
      return false;
    }
    if (!comment.pullRequest()) {
      if (command.verb().equals(SkynetCommand.RUN)
          && settings.isOrderRepository(comment.fullName())) {
        queue(
            Request.of(comment, command, Mode.ORDER, null, null, comment.issueBody())
                .withLabel(TriggerRules.ORDER_LABEL));
        return true;
      }
      List<TriggerRules.Rule> matching = new ArrayList<>();
      if (!command.verb().isEmpty()) {
        for (UUID repositoryId : comment.repositoryIds()) {
          rules.forWorkflow(repositoryId, command.verb()).ifPresent(matching::add);
        }
      }
      for (TriggerRules.Rule rule : matching) {
        queue(
            Request.of(
                    comment,
                    command,
                    Mode.ISSUE,
                    rule.id(),
                    rule.repositoryId(),
                    comment.issueBody())
                .withLabel(rule.label()));
      }
      if (!matching.isEmpty()) {
        return true;
      }
    }
    queue(Request.of(comment, command, Mode.COMMAND, null, first, command.text()));
    return true;
  }

  /** Lo que se apunta de una petición. */
  private record Request(
      String deliveryId,
      String source,
      long number,
      String url,
      String label,
      Mode mode,
      String author,
      String sender,
      String title,
      String body,
      UUID ruleId,
      UUID repositoryId,
      Long commentId,
      String command,
      boolean pullRequest,
      Instant receivedAt) {

    static Request of(GitHubIssue issue, String label, Mode mode, UUID ruleId, UUID repositoryId) {
      return new Request(
          issue.deliveryId(),
          issue.fullName(),
          issue.number(),
          issue.url(),
          label,
          mode,
          issue.author(),
          issue.sender(),
          issue.title(),
          issue.body(),
          ruleId,
          repositoryId,
          null,
          null,
          false,
          issue.receivedAt());
    }

    static Request of(
        GitHubComment comment,
        SkynetCommand command,
        Mode mode,
        UUID ruleId,
        UUID repositoryId,
        String body) {
      return new Request(
          comment.deliveryId(),
          comment.fullName(),
          comment.number(),
          comment.issueUrl(),
          "/skynet " + command.verb(),
          mode,
          comment.issueAuthor(),
          comment.sender(),
          comment.issueTitle(),
          body,
          ruleId,
          repositoryId,
          comment.commentId(),
          command.line(),
          comment.pullRequest(),
          comment.receivedAt());
    }

    Request withLabel(String label) {
      return new Request(
          deliveryId,
          source,
          number,
          url,
          label,
          mode,
          author,
          sender,
          title,
          body,
          ruleId,
          repositoryId,
          commentId,
          command,
          pullRequest,
          receivedAt);
    }
  }

  private void queue(Request request) {
    Instant now = time.now();
    jdbc.sql(
            "INSERT INTO trigger_launch (id, delivery_id, source, issue_number, issue_url, label,"
                + " mode, author, sender, title, body, trigger_id, repository_id, comment_id,"
                + " command, pull_request, status, run_after, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            request.deliveryId(),
            request.source(),
            request.number(),
            request.url(),
            request.label(),
            request.mode().name(),
            request.author(),
            request.sender(),
            cut(redactor.redact(oneLine(request.title())), MAX_TITLE),
            cut(redactor.redact(request.body() == null ? "" : request.body().strip()), MAX_BODY),
            request.ruleId(),
            request.repositoryId(),
            request.commentId(),
            request.command() == null ? null : cut(request.command(), 1000),
            request.pullRequest(),
            Timestamp.from(request.receivedAt()),
            Timestamp.from(now),
            Timestamp.from(now))
        .update();
  }

  @Scheduled(fixedDelayString = "${skynet.triggers.poll-interval:2s}")
  void poll() {
    for (int i = 0; i < 20; i++) {
      if (!processNext()) {
        return;
      }
    }
  }

  /**
   * Decide la siguiente petición pendiente.
   *
   * @return si había alguna
   */
  boolean processNext() {
    AtomicReference<Launch> claimed = new AtomicReference<>();
    try {
      transactions.executeWithoutResult(
          status -> {
            Optional<Launch> next = claim();
            if (next.isPresent()) {
              claimed.set(next.get());
              decide(next.get());
            }
          });
    } catch (InvalidRequestException | ConflictException | NotFoundException e) {
      // La transacción se ha deshecho entera (también el trabajo, si se creó): se rechaza aparte.
      Launch launch = claimed.get();
      if (launch == null) {
        throw e;
      }
      transactions.executeWithoutResult(
          status -> reject(launch, e.getMessage(), launch.repositoryId(), true));
    } catch (RuntimeException e) {
      Launch launch = claimed.get();
      if (launch == null) {
        throw e;
      }
      log.warn("No se pudo decidir la petición {} de {}", launch.id(), launch.source(), e);
      transactions.executeWithoutResult(status -> retry(launch, e));
    }
    return claimed.get() != null;
  }

  private void decide(Launch launch) {
    if (!settings.enabled()) {
      reject(launch, "Los disparadores están apagados (Ajustes → GitHub)", null, false);
      return;
    }
    if (!settings.authorized(launch.sender())) {
      reject(
          launch,
          "@" + launch.sender() + " no está entre quienes pueden lanzar desde GitHub",
          null,
          false);
      return;
    }
    if (launch.mode() == Mode.COMMAND) {
      command(launch);
      return;
    }
    if (!settings.authorized(launch.author())) {
      reject(
          launch,
          "La issue la escribió @"
              + launch.author()
              + ", que no está entre quienes pueden lanzar desde GitHub",
          null,
          false);
      return;
    }
    Target target = target(launch);
    CodeRepository repository = projects.getRepository(target.rule().repositoryId());
    Map<String, Object> inputs =
        new LinkedHashMap<>(InputPolicy.apply(target.rule().inputPolicy(), target.given()));
    inputs.putAll(target.rule().inputs());
    WorkflowDefinitionView definition =
        runs.definitions().stream()
            .filter(d -> d.key().equals(target.rule().workflowKey()))
            .findFirst()
            .orElseThrow(
                () ->
                    new InvalidRequestException(
                        "El workflow `"
                            + target.rule().workflowKey()
                            + "` no tiene ninguna versión publicada"));
    String externalRef = "github:" + launch.source() + "#" + launch.number();
    UUID workItemId =
        workItem(repository.getProjectId(), externalRef)
            .orElseGet(
                () ->
                    workItems
                        .create(
                            repository.getProjectId(),
                            launch.title().isBlank()
                                ? "Issue #" + launch.number() + " de " + launch.source()
                                : launch.title(),
                            target.description(),
                            target.rule().workItemType(),
                            externalRef)
                        .getId());
    String key = workItems.get(workItemId).getKey();
    Optional<UUID> active = activeRun(workItemId);
    if (active.isPresent()) {
      finish(
          launch,
          "DUPLICATE",
          "El trabajo " + key + " ya tiene una ejecución en marcha",
          repository.getId(),
          target.rule().id(),
          workItemId,
          active.get());
      notice(
          launch.id(),
          "Ya hay una ejecución en marcha de **"
              + key
              + "**: no lanzo otra."
              + runLink(active.get()),
          List.of(),
          List.of(),
          true);
      event(launch, repository.getId(), "trigger.duplicate", key, active.get(), null);
      return;
    }
    WorkflowRun run =
        runs.launch(
            workItemId,
            new RunService.Launch(repository.getId(), definition.id(), inputs, null, null));
    RunView view = runs.run(run.getId());
    String workflow =
        view.workflow() != null && view.workflow().name() != null
            ? "*" + view.workflow().name() + "* (`" + definition.key() + "`)"
            : "`" + definition.key() + "`";
    finish(
        launch, "LAUNCHED", null, repository.getId(), target.rule().id(), workItemId, run.getId());
    notice(
        launch.id(),
        "En marcha: **" + key + "** con " + workflow + "." + runLink(run.getId()),
        List.of(TriggerRules.RUNNING),
        List.of(TriggerRules.DONE, TriggerRules.FAILED),
        true);
    event(launch, repository.getId(), "trigger.launched", key, run.getId(), definition.key());
  }

  /** La regla, los datos y la descripción del trabajo, según el modo. */
  private record Target(TriggerRules.Rule rule, Map<String, Object> given, String description) {}

  private Target target(Launch launch) {
    if (launch.mode() == Mode.ORDER) {
      IssueBody.Order order = IssueBody.order(launch.body(), json);
      Project project =
          projects.list(Archived.EXCLUDE).stream()
              .filter(p -> p.getKey().equalsIgnoreCase(order.project()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new InvalidRequestException(
                          "No hay ningún proyecto activo con la clave " + order.project()));
      CodeRepository repository =
          projects.repositories(project.getId(), Archived.EXCLUDE).stream()
              .filter(r -> r.getName().equals(order.repository()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new InvalidRequestException(
                          "El proyecto "
                              + project.getKey()
                              + " no tiene ningún repositorio activo llamado "
                              + order.repository()));
      TriggerRules.Rule rule =
          rules
              .forWorkflow(repository.getId(), order.workflow())
              .orElseThrow(
                  () ->
                      new InvalidRequestException(
                          "El repositorio "
                              + repository.getName()
                              + " no tiene ningún disparador activo de `"
                              + order.workflow()
                              + "`"));
      return new Target(
          rule, order.parameters(), cut(redactor.redact(order.description()), MAX_BODY));
    }
    TriggerRules.Rule rule =
        launch.ruleId() == null
            ? null
            : rules.find(launch.ruleId()).filter(TriggerRules.Rule::enabled).orElse(null);
    if (rule == null) {
      throw new InvalidRequestException(
          "El disparador de la etiqueta " + launch.label() + " ya no existe o está desactivado");
    }
    Map<String, Object> given = new LinkedHashMap<>(IssueBody.inputs(launch.body(), json));
    SkynetCommand command = launch.command() == null ? null : SkynetCommand.parse(launch.command());
    if (command != null) {
      command.args().forEach((name, value) -> given.put(name, typed(rule, name, value)));
    }
    return new Target(rule, given, launch.body());
  }

  /** El valor de un {@code clave=valor} de un comentario, del tipo que pide la política. */
  private static Object typed(TriggerRules.Rule rule, String name, String value) {
    InputPolicy.Rule policy = rule.inputPolicy().get(name);
    if (policy == null) {
      return value;
    }
    return switch (policy.type()) {
      case "number" -> {
        try {
          yield new java.math.BigDecimal(value);
        } catch (NumberFormatException e) {
          throw new InvalidRequestException("El dato `" + name + "` tiene que ser un número");
        }
      }
      case "boolean" -> {
        if (!value.equals("true") && !value.equals("false")) {
          throw new InvalidRequestException("El dato `" + name + "` tiene que ser true o false");
        }
        yield Boolean.valueOf(value);
      }
      default -> value;
    };
  }

  /** Una orden que no lanza nada: la hace y deja la respuesta en GitHub. */
  private void command(Launch launch) {
    SkynetCommand command = SkynetCommand.parse(launch.command());
    if (command == null) {
      throw new InvalidRequestException("la orden no empieza por `/skynet`");
    }
    // El texto (el de corrige) es el del comentario o la revisión, ya redactado y recortado.
    command = new SkynetCommand(command.verb(), command.rest(), launch.body());
    TriggerCommands.Outcome outcome =
        commands.run(
            command, launch.source(), launch.number(), launch.pullRequest(), launch.sender());
    finish(
        launch, "DONE", null, outcome.repositoryId(), null, outcome.workItemId(), outcome.runId());
    notice(launch.id(), outcome.text(), List.of(), List.of(), true);
    if (outcome.repositoryId() != null) {
      event(
          launch,
          outcome.repositoryId(),
          "trigger.command." + outcome.verb(),
          outcome.key(),
          outcome.runId(),
          null);
    }
  }

  private Optional<UUID> workItem(UUID projectId, String externalRef) {
    return jdbc.sql("SELECT id FROM work_item WHERE project_id = ? AND external_ref = ?")
        .params(projectId, externalRef)
        .query(UUID.class)
        .optional();
  }

  private Optional<UUID> activeRun(UUID workItemId) {
    return jdbc.sql(
            "SELECT id FROM workflow_run WHERE work_item_id = ?"
                + " AND status IN ('PENDING', 'RUNNING') ORDER BY created_at DESC LIMIT 1")
        .param(workItemId)
        .query(UUID.class)
        .optional();
  }

  private String runLink(UUID runId) {
    return publicUrl.isEmpty() ? "" : " [Ver la ejecución](" + publicUrl + "/runs/" + runId + ")";
  }

  private void reject(Launch launch, String reason, UUID repositoryId, boolean tell) {
    finish(launch, "REJECTED", reason, repositoryId, launch.ruleId(), null, null);
    if (tell) {
      String text =
          launch.mode() == Mode.COMMAND
              ? "No puedo: " + reason + "."
              : "No lo lanzo: " + reason + ".";
      notice(launch.id(), text, List.of(), List.of(), true);
    }
    if (repositoryId != null) {
      event(
          launch,
          repositoryId,
          launch.mode() == Mode.COMMAND ? "trigger.command.rejected" : "trigger.rejected",
          null,
          null,
          null,
          reason);
    }
  }

  private void retry(Launch launch, RuntimeException e) {
    int attempts = launch.attempts() + 1;
    if (attempts >= MAX_ATTEMPTS) {
      reject(
          launch,
          "Error interno tras " + attempts + " intentos (" + e.getClass().getSimpleName() + ")",
          launch.repositoryId(),
          true);
      return;
    }
    jdbc.sql(
            "UPDATE trigger_launch SET attempts = ?, reason = ?, run_after = ?, updated_at = ?"
                + " WHERE id = ? AND status = 'QUEUED'")
        .params(
            attempts,
            cut(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000),
            Timestamp.from(time.now().plus(RETRY_DELAY.multipliedBy(1L << (attempts - 1)))),
            Timestamp.from(time.now()),
            launch.id())
        .update();
  }

  private void finish(
      Launch launch,
      String status,
      String reason,
      UUID repositoryId,
      UUID ruleId,
      UUID workItemId,
      UUID runId) {
    jdbc.sql(
            "UPDATE trigger_launch SET status = ?, reason = ?, repository_id = ?, trigger_id = ?,"
                + " work_item_id = ?, workflow_run_id = ?, updated_at = ? WHERE id = ?")
        .params(
            status,
            reason == null ? null : cut(reason, 1000),
            repositoryId,
            ruleId,
            workItemId,
            runId,
            Timestamp.from(time.now()),
            launch.id())
        .update();
  }

  /** Deja un aviso pendiente en GitHub; si ya había uno sin mandar, lo añade detrás. */
  void notice(UUID launchId, String text, List<String> add, List<String> remove, boolean react) {
    jdbc.sql(
            "UPDATE trigger_launch SET"
                + " notice = CASE WHEN notice IS NULL THEN ? ELSE notice || E'\\n\\n' || ? END,"
                + " notice_add = (SELECT coalesce(jsonb_agg(l), '[]'::jsonb) FROM ("
                + "   SELECT jsonb_array_elements_text(notice_add) AS l"
                + "   EXCEPT SELECT jsonb_array_elements_text(?::jsonb)"
                + "   UNION SELECT jsonb_array_elements_text(?::jsonb)) a),"
                + " notice_remove = (SELECT coalesce(jsonb_agg(l), '[]'::jsonb) FROM ("
                + "   SELECT jsonb_array_elements_text(notice_remove) AS l"
                + "   EXCEPT SELECT jsonb_array_elements_text(?::jsonb)"
                + "   UNION SELECT jsonb_array_elements_text(?::jsonb)) r),"
                + " notice_react = notice_react OR ?, notice_attempts = 0, notice_error = NULL,"
                + " notice_after = ? WHERE id = ?")
        .params(
            text,
            text,
            json.writeValueAsString(remove),
            json.writeValueAsString(add),
            json.writeValueAsString(add),
            json.writeValueAsString(remove),
            react,
            Timestamp.from(time.now()),
            launchId)
        .update();
  }

  private void event(
      Launch launch, UUID repositoryId, String type, String workItemKey, UUID runId, String flow) {
    event(launch, repositoryId, type, workItemKey, runId, flow, null);
  }

  private void event(
      Launch launch,
      UUID repositoryId,
      String type,
      String workItemKey,
      UUID runId,
      String workflow,
      String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("launchId", launch.id());
    payload.put("source", launch.source());
    payload.put("issue", launch.number());
    payload.put("label", launch.label());
    payload.put("mode", launch.mode().name());
    if (launch.sender() != null) {
      payload.put("sender", launch.sender());
    }
    if (launch.command() != null) {
      payload.put("command", launch.command());
    }
    if (workItemKey != null) {
      payload.put("workItemKey", workItemKey);
    }
    if (workflow != null) {
      payload.put("workflow", workflow);
    }
    if (reason != null) {
      payload.put("reason", reason);
    }
    events.append(EventDraft.of(AGGREGATE, repositoryId, type, runId, payload, time.now()));
  }

  /** Una petición pendiente, tal como se guardó. */
  record Launch(
      UUID id,
      String source,
      long number,
      String label,
      Mode mode,
      String author,
      String sender,
      String title,
      String body,
      UUID ruleId,
      UUID repositoryId,
      int attempts,
      String command,
      boolean pullRequest) {}

  private Optional<Launch> claim() {
    return jdbc.sql(
            "SELECT id, source, issue_number, label, mode, author, sender, title, body,"
                + " trigger_id, repository_id, attempts, command, pull_request FROM trigger_launch"
                + " WHERE status = 'QUEUED' AND run_after <= ?"
                + " ORDER BY run_after LIMIT 1 FOR UPDATE SKIP LOCKED")
        .param(Timestamp.from(time.now()))
        .query(
            (rs, n) ->
                new Launch(
                    rs.getObject("id", UUID.class),
                    rs.getString("source"),
                    rs.getLong("issue_number"),
                    rs.getString("label"),
                    Mode.valueOf(rs.getString("mode")),
                    rs.getString("author"),
                    rs.getString("sender"),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getObject("trigger_id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getInt("attempts"),
                    rs.getString("command"),
                    rs.getBoolean("pull_request")))
        .optional();
  }

  static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
  }
}
