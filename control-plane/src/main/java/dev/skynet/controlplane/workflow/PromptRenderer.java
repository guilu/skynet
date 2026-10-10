package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.workitem.WorkItem;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sustituye las variables {@code {{...}}} de un prompt del YAML. El texto que se sustituye no se
 * vuelve a leer, así que un dato que contenga {@code {{...}}} llega tal cual al agente.
 */
final class PromptRenderer {

  private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]*)}}");

  private final Map<String, String> values = new HashMap<>();

  /** Variables del trabajo, su proyecto y los datos de entrada del lanzamiento. */
  PromptRenderer(WorkItem workItem, Project project, Map<String, Object> inputs) {
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
      String value = values.get(matcher.group(1).strip());
      matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String text(Object value) {
    return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
  }
}
