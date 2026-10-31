package dev.skynet.controlplane.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;

/**
 * El resumen de una entrega que va en su evento {@code github.*}: lo que harán falta EN y DS
 * (número, rama, SHA, estado, conclusión…), no el cuerpo entero, que se queda en la entrega.
 */
final class WebhookSummary {

  /** Lo más que se copia del texto de un comentario o una revisión. */
  static final int MAX_TEXT = 1000;

  private WebhookSummary() {}

  /**
   * @param redact oculta los secretos de un texto libre (comentarios y revisiones)
   */
  static Map<String, Object> of(
      String deliveryId, String event, JsonNode payload, UnaryOperator<String> redact) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("deliveryId", deliveryId);
    summary.put("event", event);
    put(summary, "action", payload.path("action"));
    put(summary, "repository", payload.path("repository").path("full_name"));
    put(summary, "sender", payload.path("sender").path("login"));
    switch (event) {
      case "pull_request" -> pullRequest(summary, payload.path("pull_request"));
      case "pull_request_review" -> {
        pullRequest(summary, payload.path("pull_request"));
        JsonNode review = payload.path("review");
        put(summary, "reviewId", review.path("id"));
        put(summary, "reviewState", review.path("state"));
        put(summary, "reviewer", review.path("user").path("login"));
        put(summary, "reviewUrl", review.path("html_url"));
        text(summary, "reviewBody", review.path("body"), redact);
      }
      case "check_suite" -> {
        JsonNode suite = payload.path("check_suite");
        put(summary, "checkSuiteId", suite.path("id"));
        put(summary, "status", suite.path("status"));
        put(summary, "conclusion", suite.path("conclusion"));
        put(summary, "headBranch", suite.path("head_branch"));
        put(summary, "headSha", suite.path("head_sha"));
        put(summary, "app", suite.path("app").path("slug"));
        summary.put("pullRequests", numbers(suite.path("pull_requests")));
      }
      case "check_run" -> {
        JsonNode run = payload.path("check_run");
        put(summary, "checkRunId", run.path("id"));
        put(summary, "name", run.path("name"));
        put(summary, "status", run.path("status"));
        put(summary, "conclusion", run.path("conclusion"));
        put(summary, "headSha", run.path("head_sha"));
        put(summary, "url", run.path("html_url"));
        put(summary, "app", run.path("app").path("slug"));
        put(summary, "checkSuiteId", run.path("check_suite").path("id"));
        summary.put("pullRequests", numbers(run.path("pull_requests")));
      }
      case "status" -> {
        put(summary, "sha", payload.path("sha"));
        put(summary, "state", payload.path("state"));
        put(summary, "context", payload.path("context"));
        put(summary, "description", payload.path("description"));
        put(summary, "targetUrl", payload.path("target_url"));
      }
      case "issues" -> {
        issue(summary, payload.path("issue"));
        put(summary, "label", payload.path("label").path("name"));
      }
      case "issue_comment" -> {
        issue(summary, payload.path("issue"));
        JsonNode comment = payload.path("comment");
        put(summary, "commentId", comment.path("id"));
        put(summary, "commentAuthor", comment.path("user").path("login"));
        put(summary, "commentUrl", comment.path("html_url"));
        text(summary, "commentBody", comment.path("body"), redact);
      }
      default -> {
        // Los demás eventos solo llevan lo común.
      }
    }
    return summary;
  }

  private static void pullRequest(Map<String, Object> summary, JsonNode pr) {
    put(summary, "number", pr.path("number"));
    put(summary, "title", pr.path("title"));
    put(summary, "state", pr.path("state"));
    put(summary, "draft", pr.path("draft"));
    put(summary, "merged", pr.path("merged"));
    put(summary, "author", pr.path("user").path("login"));
    put(summary, "headRef", pr.path("head").path("ref"));
    put(summary, "headSha", pr.path("head").path("sha"));
    put(summary, "baseRef", pr.path("base").path("ref"));
    put(summary, "url", pr.path("html_url"));
  }

  private static void issue(Map<String, Object> summary, JsonNode issue) {
    put(summary, "number", issue.path("number"));
    put(summary, "title", issue.path("title"));
    put(summary, "state", issue.path("state"));
    put(summary, "author", issue.path("user").path("login"));
    put(summary, "url", issue.path("html_url"));
    summary.put("isPullRequest", issue.has("pull_request"));
    List<String> labels = new ArrayList<>();
    issue.path("labels").forEach(label -> labels.add(label.path("name").asString()));
    summary.put("labels", labels);
  }

  private static List<Long> numbers(JsonNode pullRequests) {
    List<Long> numbers = new ArrayList<>();
    pullRequests.forEach(pr -> numbers.add(pr.path("number").asLong()));
    return numbers;
  }

  private static void put(Map<String, Object> summary, String key, JsonNode value) {
    if (value.isString()) {
      summary.put(key, value.asString());
    } else if (value.isIntegralNumber()) {
      summary.put(key, value.asLong());
    } else if (value.isBoolean()) {
      summary.put(key, value.asBoolean());
    }
  }

  private static void text(
      Map<String, Object> summary, String key, JsonNode value, UnaryOperator<String> redact) {
    if (value.isString()) {
      String text = redact.apply(value.asString());
      summary.put(key, text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text);
    }
  }
}
