package dev.skynet.controlplane.event;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filtro opcional por ejecución de workflow, agregado y tipos de evento. {@code null} significa
 * "cualquiera".
 *
 * <p>Cada tipo es exacto ({@code workflow.status.changed}) o un prefijo terminado en {@code .*}
 * ({@code approval.*}, que admite {@code approval.requested} y {@code approval.granted}).
 */
public record EventFilter(UUID workflowRunId, UUID aggregateId, List<String> types) {

  public static final EventFilter ALL = new EventFilter(null, null);

  private static final Pattern TYPE = Pattern.compile("[a-z0-9_]++(?:\\.[a-z0-9_]++)*+(?:\\.\\*)?");

  public EventFilter {
    types = types == null || types.isEmpty() ? null : List.copyOf(types);
  }

  public EventFilter(UUID workflowRunId, UUID aggregateId) {
    this(workflowRunId, aggregateId, null);
  }

  /**
   * Lee la lista separada por comas de la petición (vacía: cualquiera); rechaza tipos mal formados.
   */
  static List<String> parseTypes(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<String> types = Arrays.stream(csv.split(",")).map(String::strip).toList();
    for (String type : types) {
      if (!TYPE.matcher(type).matches()) {
        throw new InvalidRequestException("Tipo de evento no válido: «" + type + "»");
      }
    }
    return types;
  }

  public boolean matches(StoredEvent event) {
    return (workflowRunId == null || workflowRunId.equals(event.workflowRunId()))
        && (aggregateId == null || aggregateId.equals(event.aggregateId()))
        && (types == null || types.stream().anyMatch(t -> typeMatches(t, event.type())));
  }

  private static boolean typeMatches(String pattern, String type) {
    return pattern.endsWith(".*")
        ? type.startsWith(pattern.substring(0, pattern.length() - 1))
        : pattern.equals(type);
  }

  /** Los tipos como patrones de {@code LIKE}, separados por comas, o {@code null}. */
  String likePatterns() {
    if (types == null) {
      return null;
    }
    return String.join(
        ",",
        types.stream()
            .map(
                t ->
                    t.endsWith(".*")
                        ? t.substring(0, t.length() - 1).replace("_", "\\_") + "%"
                        : t.replace("_", "\\_"))
            .toList());
  }
}
