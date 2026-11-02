package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lo que se lee del cuerpo de una issue: la orden de un repositorio de órdenes o los datos. */
final class IssueBody {

  /** El cuerpo entero tiene que ser solo el bloque, como en el puente. */
  private static final Pattern ORDER =
      Pattern.compile("```skynet-order[ \\t]*\\R(.*?)\\R```", Pattern.DOTALL);

  /** Un bloque opcional con los datos del workflow, en cualquier parte de la issue. */
  private static final Pattern INPUTS =
      Pattern.compile("```skynet[ \\t]*\\R(.*?)\\R```", Pattern.DOTALL);

  static final Set<String> ORDER_KEYS =
      Set.of("skynet_project", "target_repository", "workflow", "parameters", "description");

  /** Lo más largo que puede ocupar el JSON de los datos. */
  static final int MAX_JSON = 20_000;

  /**
   * Una orden de un repositorio de órdenes (el contrato {@code skynet-order} del puente).
   *
   * @param project la clave del proyecto de Skynet
   * @param repository el nombre del repositorio en ese proyecto
   */
  record Order(
      String project,
      String repository,
      String workflow,
      Map<String, Object> parameters,
      String description) {}

  private IssueBody() {}

  /**
   * @throws InvalidRequestException si el cuerpo no es solo un bloque {@code skynet-order} válido
   */
  static Order order(String body, ObjectMapper json) {
    Matcher m = ORDER.matcher(body == null ? "" : body.strip());
    if (!m.matches()) {
      throw new InvalidRequestException(
          "La issue tiene que ser solo un bloque ```skynet-order con la orden en JSON");
    }
    JsonNode order = parse(m.group(1), json);
    Set<String> keys = new java.util.HashSet<>(order.propertyNames());
    if (!keys.equals(ORDER_KEYS)) {
      throw new InvalidRequestException(
          "La orden tiene que llevar exactamente "
              + String.join(", ", ORDER_KEYS.stream().sorted().toList()));
    }
    for (String key : ORDER_KEYS) {
      if (!key.equals("parameters")
          && (!order.path(key).isString() || order.path(key).asString().isBlank())) {
        throw new InvalidRequestException("La orden no trae `" + key + "`");
      }
    }
    if (!order.path("parameters").isObject()) {
      throw new InvalidRequestException("`parameters` tiene que ser un objeto JSON");
    }
    return new Order(
        order.path("skynet_project").asString().strip(),
        order.path("target_repository").asString().strip(),
        order.path("workflow").asString().strip(),
        values(order.path("parameters"), json),
        order.path("description").asString().strip());
  }

  /**
   * Los datos del bloque {@code ```skynet} de la issue, o vacío si no tiene.
   *
   * @throws InvalidRequestException si el bloque no es un objeto JSON
   */
  static Map<String, Object> inputs(String body, ObjectMapper json) {
    Matcher m = INPUTS.matcher(body == null ? "" : body);
    if (!m.find()) {
      return Map.of();
    }
    JsonNode inputs = parse(m.group(1), json);
    return values(inputs, json);
  }

  private static JsonNode parse(String text, ObjectMapper json) {
    if (text.length() > MAX_JSON) {
      throw new InvalidRequestException(
          "El bloque es demasiado largo: como mucho 20000 caracteres");
    }
    try {
      JsonNode node = json.readTree(text);
      if (node == null || !node.isObject()) {
        throw new InvalidRequestException("El bloque tiene que ser un objeto JSON");
      }
      return node;
    } catch (JacksonException e) {
      throw new InvalidRequestException("El bloque no es JSON válido");
    }
  }

  private static Map<String, Object> values(JsonNode object, ObjectMapper json) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> field : object.properties()) {
      JsonNode value = field.getValue();
      if (!(value.isString() || value.isNumber() || value.isBoolean())) {
        throw new InvalidRequestException(
            "El dato `" + field.getKey() + "` tiene que ser un texto, un número o un sí/no");
      }
      values.put(field.getKey(), json.treeToValue(value, Object.class));
    }
    return values;
  }
}
