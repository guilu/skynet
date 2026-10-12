package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Un proveedor de agentes que el runner tiene disponible.
 *
 * @param id identificador del proveedor (p. ej. {@code claude-code}); ver {@link
 *     dev.skynet.protocol.AgentProviderKind}
 * @param version versión de su CLI ({@code --version}), o {@code null} si no se pudo determinar
 */
public record ProviderInfo(String id, String version) {

  public ProviderInfo {
    Objects.requireNonNull(id, "id");
  }
}
