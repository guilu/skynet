package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.workitem.WorkItemType;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Los disparadores: sus ajustes, las reglas de cada repositorio y las peticiones recibidas. */
@RestController
class TriggerController {

  private final TriggerSettings settings;
  private final TriggerRules rules;
  private final JdbcClient jdbc;

  TriggerController(TriggerSettings settings, TriggerRules rules, JdbcClient jdbc) {
    this.settings = settings;
    this.rules = rules;
    this.jdbc = jdbc;
  }

  record SaveSettings(
      Boolean enabled,
      @Size(max = 100) List<String> authorizedLogins,
      @Size(max = 100) List<String> orderRepositories,
      Integer dailyLimit) {}

  record SaveRule(
      TriggerRules.Kind kind,
      String workflowKey,
      String label,
      WorkItemType workItemType,
      @Size(max = 100) Map<String, Object> inputs,
      @Size(max = 50) Map<String, InputPolicy.Rule> inputPolicy,
      Boolean enabled,
      @Size(max = 200) String cron,
      @Size(max = 100) String timeZone,
      @Size(max = 300) String workItemTitle) {

    TriggerRules.Draft draft() {
      return new TriggerRules.Draft(
          kind,
          workflowKey,
          label,
          workItemType,
          inputs,
          inputPolicy,
          enabled,
          cron,
          timeZone,
          workItemTitle);
    }
  }

  /** Una petición recibida, sin el cuerpo de la issue. */
  record LaunchView(
      UUID id,
      String deliveryId,
      String source,
      long issueNumber,
      String issueUrl,
      String label,
      String mode,
      String command,
      boolean pullRequest,
      String author,
      String sender,
      String title,
      String status,
      String reason,
      UUID triggerId,
      UUID repositoryId,
      UUID workItemId,
      UUID workflowRunId,
      boolean noticePending,
      String noticeError,
      Instant createdAt,
      Instant updatedAt) {}

  @GetMapping("/api/triggers/settings")
  TriggerSettings.View settings() {
    return settings.view();
  }

  @PutMapping("/api/triggers/settings")
  TriggerSettings.View saveSettings(@RequestBody @jakarta.validation.Valid SaveSettings request) {
    return settings.save(
        request.enabled(),
        request.authorizedLogins(),
        request.orderRepositories(),
        request.dailyLimit());
  }

  @GetMapping("/api/repositories/{repositoryId}/triggers")
  List<TriggerRules.Rule> ofRepository(@PathVariable UUID repositoryId) {
    return rules.ofRepository(repositoryId);
  }

  @PostMapping("/api/repositories/{repositoryId}/triggers")
  @ResponseStatus(HttpStatus.CREATED)
  TriggerRules.Rule create(
      @PathVariable UUID repositoryId, @RequestBody @jakarta.validation.Valid SaveRule request) {
    return rules.create(repositoryId, request.draft());
  }

  @GetMapping("/api/triggers/{id}")
  TriggerRules.Rule get(@PathVariable UUID id) {
    return rules.get(id);
  }

  @PutMapping("/api/triggers/{id}")
  TriggerRules.Rule update(
      @PathVariable UUID id, @RequestBody @jakarta.validation.Valid SaveRule request) {
    return rules.update(id, request.draft());
  }

  @DeleteMapping("/api/triggers/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    rules.delete(id);
  }

  /** Las últimas peticiones, de la más reciente a la más antigua. */
  @PostMapping("/api/triggers/{id}/fire")
  TriggerRules.Rule fire(@PathVariable UUID id) {
    return rules.fireNow(id);
  }

  @GetMapping("/api/triggers/launches")
  List<LaunchView> launches(
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) UUID repositoryId) {
    if (limit < 1 || limit > 200) {
      throw new InvalidRequestException("limit tiene que estar entre 1 y 200");
    }
    return jdbc.sql(
            "SELECT * FROM trigger_launch WHERE (CAST(:repo AS uuid) IS NULL OR repository_id ="
                + " :repo) ORDER BY created_at DESC, id LIMIT :limit")
        .param("repo", repositoryId)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new LaunchView(
                    rs.getObject("id", UUID.class),
                    rs.getString("delivery_id"),
                    rs.getString("source"),
                    rs.getLong("issue_number"),
                    rs.getString("issue_url"),
                    rs.getString("label"),
                    rs.getString("mode"),
                    rs.getString("command"),
                    rs.getBoolean("pull_request"),
                    rs.getString("author"),
                    rs.getString("sender"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("reason"),
                    rs.getObject("trigger_id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getObject("work_item_id", UUID.class),
                    rs.getObject("workflow_run_id", UUID.class),
                    rs.getTimestamp("notice_after") != null,
                    rs.getString("notice_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }
}
