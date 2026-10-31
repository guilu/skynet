package dev.skynet.controlplane.github;

import dev.skynet.controlplane.github.WebhookQueue.Status;
import dev.skynet.controlplane.shared.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Los webhooks de la GitHub App. {@code POST /api/github/webhooks} no lleva sesión: lo protege solo
 * la firma {@code X-Hub-Signature-256}. Responde enseguida (202) y la entrega se procesa en la
 * cola.
 */
@RestController
class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  /** Lo que GitHub pone en {@code X-GitHub-Delivery}: un UUID (o un id parecido). */
  private static final Pattern DELIVERY_ID = Pattern.compile("^[A-Za-z0-9-]{1,100}$");

  private static final Pattern EVENT = Pattern.compile("^[a-z_]{1,64}$");

  private static final int MAX_LIMIT = 200;

  private final GitHubProperties properties;
  private final WebhookProperties webhooks;
  private final WebhookQueue queue;
  private final ObjectMapper json;

  WebhookController(
      GitHubProperties properties,
      WebhookProperties webhooks,
      WebhookQueue queue,
      ObjectMapper json) {
    this.properties = properties;
    this.webhooks = webhooks;
    this.queue = queue;
    this.json = json;
  }

  /**
   * Recibe una entrega: 401 si la firma no vale (y no se guarda), 200 si ya se tenía, 202 si es
   * nueva. 503 si la App no está configurada: GitHub la marcará fallida y se pedirá otra vez.
   */
  @PostMapping("/api/github/webhooks")
  ResponseEntity<ProblemDetail> receive(HttpServletRequest request) throws IOException {
    if (!properties.configured()) {
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "La GitHub App no está configurada");
    }
    long max = webhooks.maxPayload().toBytes();
    if (request.getContentLengthLong() > max) {
      return problem(HttpStatus.CONTENT_TOO_LARGE, "La entrega pasa de " + max + " bytes");
    }
    byte[] body;
    try (InputStream in = request.getInputStream()) {
      body = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
    }
    if (body.length > max) {
      return problem(HttpStatus.CONTENT_TOO_LARGE, "La entrega pasa de " + max + " bytes");
    }
    if (!WebhookSignature.matches(
        properties.webhookSecret(), body, request.getHeader("X-Hub-Signature-256"))) {
      log.warn("Entrega de GitHub con firma no válida desde {}", request.getRemoteAddr());
      return problem(HttpStatus.UNAUTHORIZED, "Firma X-Hub-Signature-256 no válida");
    }
    String deliveryId = request.getHeader("X-GitHub-Delivery");
    String event = request.getHeader("X-GitHub-Event");
    if (deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches()) {
      return problem(HttpStatus.BAD_REQUEST, "Falta X-GitHub-Delivery o no es válido");
    }
    if (event == null || !EVENT.matcher(event).matches()) {
      return problem(HttpStatus.BAD_REQUEST, "Falta X-GitHub-Event o no es válido");
    }
    String text = new String(body, StandardCharsets.UTF_8);
    JsonNode payload;
    try {
      payload = json.readTree(text);
    } catch (JacksonException e) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "El cuerpo no es JSON: en la App, el webhook tiene que ir con Content type"
              + " application/json");
    }
    if (!payload.isObject()) {
      return problem(HttpStatus.BAD_REQUEST, "El cuerpo no es un objeto JSON");
    }
    boolean ping = event.equals("ping");
    boolean inserted =
        queue.insert(
            deliveryId,
            event,
            payload,
            text,
            ping ? Status.IGNORED : Status.PENDING,
            ping ? "ping: GitHub comprueba el webhook" : null);
    return ResponseEntity.status(inserted ? HttpStatus.ACCEPTED : HttpStatus.OK).build();
  }

  /** Las últimas entregas, sin su cuerpo, para Ajustes → GitHub. */
  @GetMapping("/api/github/deliveries")
  List<WebhookDelivery> deliveries(@RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidRequestException("limit va de 1 a " + MAX_LIMIT);
    }
    return queue.latest(limit);
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}
