package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los supuestos de una ejecución (ES-C): lo que los agentes dijeron haber supuesto sin poder
 * comprobarlo, en la propiedad {@link StageDefinition#ASSUMPTIONS} de su salida estructurada. No
 * paran nada: van a la PR, a la puerta de fusión y a la web para revisarlos después.
 */
final class RunAssumptions {

  /** Más que esto por ejecución no se lee: se enseñan los primeros. */
  static final int MAX = 30;

  /** Un supuesto ya no cabe en una frase larga: se corta. */
  static final int MAX_LENGTH = 400;

  /** Un supuesto, con la fase que lo dejó. */
  record Assumption(String stageKey, String text) {}

  private RunAssumptions() {}

  /**
   * Los supuestos del último intento de cada fase, en el orden en que salieron y sin repetir (una
   * corrección que los vuelve a decir no los duplica).
   */
  static List<Assumption> of(JdbcClient jdbc, UUID runId) {
    List<String[]> rows =
        jdbc.sql(
                """
                SELECT s.stage_key, a.structured_output
                FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id
                WHERE s.workflow_run_id = ? AND a.structured_output IS NOT NULL
                  AND s.attempt = (SELECT max(x.attempt) FROM stage_run x
                                   WHERE x.workflow_run_id = s.workflow_run_id
                                     AND x.stage_key = s.stage_key)
                ORDER BY a.created_at, a.id
                """)
            .param(runId)
            .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
            .list();
    Set<Assumption> seen = new LinkedHashSet<>();
    for (String[] row : rows) {
      for (String text : parse(row[1])) {
        if (seen.size() >= MAX) {
          return List.copyOf(seen);
        }
        seen.add(new Assumption(row[0], text));
      }
    }
    return List.copyOf(seen);
  }

  /** Los textos de {@code assumptions} de una salida estructurada; vacío si no hay o no se lee. */
  static List<String> parse(String structuredOutput) {
    if (structuredOutput == null || structuredOutput.isBlank()) {
      return List.of();
    }
    JsonNode node;
    try {
      node = JsonMapper.shared().readTree(structuredOutput);
    } catch (RuntimeException e) {
      return List.of();
    }
    JsonNode list = node == null ? null : node.get(StageDefinition.ASSUMPTIONS);
    if (list == null || !list.isArray()) {
      return List.of();
    }
    List<String> texts = new ArrayList<>();
    for (JsonNode item : list) {
      if (item.isString() && !item.asString().isBlank()) {
        String text = item.asString().strip().replaceAll("\\s+", " ");
        texts.add(text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH - 1) + "…" : text);
      }
    }
    return texts;
  }
}
