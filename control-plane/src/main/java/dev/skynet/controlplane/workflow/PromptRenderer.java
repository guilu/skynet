package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.Condition;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.workitem.WorkItem;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sustituye las variables {@code {{...}}} de un prompt del YAML. El texto que se sustituye no se
 * vuelve a leer, así que un dato que contenga {@code {{...}}} llega tal cual al agente.
 *
 * <p>Las variables {@code {{stages.<fase>.output.<campo>}}}, {@code
 * {{stages.<fase>.<hijo>.output.<campo>}}}, {@code {{stages.<fase>.status}}} y {@code
 * {{stages.<fase>.exitCode}}} leen los mismos datos que las condiciones: un texto entra tal cual y
 * lo demás como JSON. Lo que falta (una fase omitida, un campo que no devolvió) queda vacío.
 */
final class PromptRenderer {

  private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]*)}}");

  private final Map<String, String> values = new HashMap<>();
  private final Function<List<String>, Object> stages;

  /** Variables del trabajo, su proyecto y los datos de entrada del lanzamiento. */
  PromptRenderer(WorkItem workItem, Project project, Map<String, Object> inputs) {
    this(workItem, project, inputs, parts -> null);
  }

  /**
   * Además, los datos de otras fases: {@code stages} recibe la ruta como la de una condición
   * ({@code [fase, campo]}, {@code [fase, hijo, campo]} o {@code [fase, status]}).
   */
  PromptRenderer(
      WorkItem workItem,
      Project project,
      Map<String, Object> inputs,
      Function<List<String>, Object> stages) {
    this.stages = stages;
    values.put("workItem.key", workItem.getKey());
    values.put("workItem.title", workItem.getTitle());
    values.put("workItem.description", workItem.getDescription());
    values.put("workItem.type", workItem.getType() == null ? null : workItem.getType().name());
    values.put("workItem.externalRef", workItem.getExternalRef());
    values.put("project.key", project.getKey());
    values.put("project.name", project.getName());
    inputs.forEach((name, value) -> values.put("inputs." + name, text(value)));
  }

  /** El prompt con cada variable sustituida; una sin valor (un dato opcional vacío) queda vacía. */
  String render(String template) {
    Matcher matcher = VARIABLE.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String variable = matcher.group(1).strip();
      String value = variable.startsWith("stages.") ? stage(variable) : values.get(variable);
      matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String stage(String variable) {
    List<String> path = Condition.promptPath(variable);
    Object value = path == null ? null : stages.apply(path);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    return value instanceof BigDecimal || value instanceof Number || value instanceof Boolean
        ? text(value)
        : JsonMapper.shared().writeValueAsString(value);
  }

  private static String text(Object value) {
    return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
  }
}
