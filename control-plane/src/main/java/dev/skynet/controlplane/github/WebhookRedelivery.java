package dev.skynet.controlplane.github;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Red de seguridad para cuando el túnel o este equipo estuvieron caídos: pide a GitHub las últimas
 * entregas de la App ({@code GET /app/hook/deliveries}) y vuelve a solicitar las que fallaron y no
 * están en Skynet. Al arrancar y cada {@code redelivery-interval}.
 */
@Service
class WebhookRedelivery {

  private static final Logger log = LoggerFactory.getLogger(WebhookRedelivery.class);

  private final GitHubProperties properties;
  private final GitHubApi api;
  private final WebhookQueue queue;

  /** Las entregas (por su guid) ya pedidas, para no repetirlas en cada pasada. */
  private final Set<String> requested = new HashSet<>();

  WebhookRedelivery(GitHubProperties properties, GitHubApi api, WebhookQueue queue) {
    this.properties = properties;
    this.api = api;
    this.queue = queue;
  }

  @Scheduled(
      initialDelayString = "${skynet.github.webhooks.redelivery-initial-delay:30s}",
      fixedDelayString = "${skynet.github.webhooks.redelivery-interval:10m}")
  void scheduled() {
    if (!properties.configured()) {
      return;
    }
    try {
      redeliverMissing();
    } catch (GitHubException e) {
      log.warn("No se pudieron revisar las entregas de GitHub: {}", e.getMessage());
    }
  }

  /**
   * Pide otra vez cada entrega que GitHub no consiguió entregar (ningún intento con 2xx) y que
   * Skynet no tiene. Devuelve cuántas pidió.
   */
  synchronized int redeliverMissing() {
    // Por guid (el X-GitHub-Delivery), el intento más reciente y si alguno llegó bien.
    Map<String, JsonNode> latest = new LinkedHashMap<>();
    Set<String> delivered = new HashSet<>();
    for (JsonNode attempt : api.hookDeliveries()) {
      String guid = attempt.path("guid").asString("");
      if (guid.isEmpty()) {
        continue;
      }
      int code = attempt.path("status_code").asInt(0);
      if (code >= 200 && code < 300) {
        delivered.add(guid);
      }
      JsonNode current = latest.get(guid);
      if (current == null || attempt.path("id").asLong() > current.path("id").asLong()) {
        latest.put(guid, attempt);
      }
    }
    List<JsonNode> missing =
        latest.entrySet().stream()
            .filter(e -> !delivered.contains(e.getKey()))
            .filter(e -> !requested.contains(e.getKey()))
            .filter(e -> !queue.exists(e.getKey()))
            .map(Map.Entry::getValue)
            .toList();
    if (requested.size() > 10_000) {
      requested.clear();
    }
    for (JsonNode attempt : missing) {
      api.redeliver(attempt.path("id").asLong());
      requested.add(attempt.path("guid").asString());
    }
    if (!missing.isEmpty()) {
      log.info("Pedidas otra vez {} entregas de GitHub que no llegaron", missing.size());
    }
    return missing.size();
  }
}
