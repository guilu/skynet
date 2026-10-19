package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.protocol.AgentProviderKind;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proveedor y modelo elegidos al lanzar para las fases de agente sin agente con nombre (como la de
 * {@code adhoc}); se guardan en {@code workflow_run.launch_provider} y {@code launch_model}.
 *
 * @param provider id del proveedor, o {@code null} para Claude Code
 * @param model modelo, o {@code null} para el predeterminado del proveedor
 */
record LaunchAgent(String provider, String model) {

  /** Lo que admite un nombre de modelo: va a la línea de comandos del CLI tal cual. */
  private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,99}");

  static final LaunchAgent DEFAULT = new LaunchAgent(null, null);

  /**
   * El elegido en un lanzamiento: valida el proveedor y el modelo; vacío cuenta como sin elegir.
   */
  static LaunchAgent of(String provider, String model) {
    String p = provider == null || provider.isBlank() ? null : provider.strip();
    String m = model == null || model.isBlank() ? null : model.strip();
    if (p != null && AgentProviderKind.byId(p).filter(AgentProviderKind::retired).isPresent()) {
      throw new InvalidRequestException(
          AgentProviderKind.byId(p).orElseThrow().displayName()
              + " se ha retirado: elige otro proveedor");
    }
    if (p != null && AgentProviderKind.byId(p).isEmpty()) {
      throw new InvalidRequestException(
          "El proveedor `"
              + p
              + "` no existe. Puede ser "
              + AgentProviderKind.active().stream()
                  .map(k -> "`" + k.id() + "`")
                  .collect(Collectors.joining(", ")));
    }
    if (m != null && !MODEL.matcher(m).matches()) {
      throw new InvalidRequestException(
          "El modelo `" + m + "` no es válido: letras, números y . _ : / @ -");
    }
    return new LaunchAgent(AgentProviderKind.CLAUDE_CODE.id().equals(p) ? null : p, m);
  }

  /** El guardado en una ejecución. */
  static LaunchAgent of(JdbcClient jdbc, UUID workflowRunId) {
    return jdbc.sql("SELECT launch_provider, launch_model FROM workflow_run WHERE id = ?")
        .param(workflowRunId)
        .query((rs, row) -> new LaunchAgent(rs.getString(1), rs.getString(2)))
        .single();
  }

  boolean isDefault() {
    return provider == null && model == null;
  }
}
