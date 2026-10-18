package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Un proveedor de agentes que el runner tiene disponible.
 *
 * @param id identificador del proveedor (p. ej. {@code claude-code}); ver {@link
 *     dev.skynet.protocol.AgentProviderKind}
 * @param version versión de su CLI ({@code --version}), o {@code null} si no se pudo determinar
 * @param simulated si el CLI es un simulador de pruebas (fake-claude, fake-codex…) y no el real. Se
 *     deduce de la versión: los simuladores la anuncian con el sufijo {@code (fake-…)}
 */
public record ProviderInfo(String id, String version, Boolean simulated) {

  public ProviderInfo {
    Objects.requireNonNull(id, "id");
    // Nunca null. También con runners anteriores a AR-A, que no lo declaran: basta con su versión.
    simulated = Boolean.TRUE.equals(simulated) || isSimulated(version);
  }

  public ProviderInfo(String id, String version) {
    this(id, version, false);
  }

  /** Si una versión de {@code --version} es la de un simulador ({@code 2.1.0 (fake-claude)}). */
  public static boolean isSimulated(String version) {
    return version != null && version.contains("(fake-");
  }
}
