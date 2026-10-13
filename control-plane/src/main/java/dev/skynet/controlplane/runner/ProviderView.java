package dev.skynet.controlplane.runner;

import dev.skynet.protocol.AgentProviderKind.Capability;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Un proveedor de agentes que Skynet sabe ejecutar, para la web: qué admite y cuántos runners en
 * línea lo tienen.
 *
 * @param id id del proveedor en el YAML (p. ej. {@code codex})
 * @param displayName nombre para mostrar (p. ej. {@code Codex})
 * @param capabilities lo que admite además de arrancar una sesión
 * @param onlineRunners runners en línea (no olvidados) que lo tienen
 */
public record ProviderView(
    String id, String displayName, Set<Capability> capabilities, int onlineRunners) {

  public ProviderView {
    // En el orden del enum, para que el JSON sea estable.
    capabilities = Collections.unmodifiableSet(new TreeSet<>(capabilities));
  }
}
