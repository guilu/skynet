package dev.skynet.runner.provider;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.ProviderInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Proveedores que conoce el runner y cuáles tiene disponibles. Al arrancar prueba {@code --version}
 * de cada CLI y declara los que responden; con {@code SKYNET_PROVIDERS} se declaran exactamente los
 * indicados (aunque su CLI no responda a {@code --version}).
 */
public final class ProviderRegistry {

  private static final Logger LOG = Logger.getLogger(ProviderRegistry.class.getName());

  private final Map<String, AgentProvider> known = new LinkedHashMap<>();
  private final Map<String, ProviderInfo> available = new LinkedHashMap<>();

  private ProviderRegistry(List<AgentProvider> providers) {
    providers.forEach(p -> known.put(p.kind().id(), p));
  }

  /**
   * @param providers adaptadores que conoce el runner; el primero da el entorno de los comandos y
   *     las verificaciones
   * @param only proveedores que declarar ({@code SKYNET_PROVIDERS}), o {@code null} para los que
   *     respondan a {@code --version}
   */
  public static ProviderRegistry detect(
      List<AgentProvider> providers, Set<String> only, Map<String, String> runnerEnv) {
    ProviderRegistry registry = new ProviderRegistry(providers);
    if (only != null) {
      only.stream()
          .filter(id -> !registry.known.containsKey(id))
          .forEach(id -> LOG.warning(() -> "SKYNET_PROVIDERS: proveedor desconocido " + id));
    }
    for (AgentProvider provider : providers) {
      String id = provider.kind().id();
      if (only != null && !only.contains(id)) {
        continue;
      }
      Optional<String> version = provider.version(runnerEnv);
      if (version.isPresent() || only != null) {
        registry.available.put(id, new ProviderInfo(id, version.orElse(null)));
      } else {
        LOG.info(
            () ->
                provider.kind().displayName()
                    + " no está disponible: "
                    + provider.executable()
                    + " --version no respondió");
      }
    }
    if (registry.available.isEmpty()) {
      LOG.warning(
          "El runner no tiene ningún proveedor de agentes disponible: solo recibirá comandos");
    }
    return registry;
  }

  /** Registro con estos proveedores disponibles, sin probar sus CLIs (para pruebas). */
  public static ProviderRegistry of(AgentProvider... providers) {
    ProviderRegistry registry = new ProviderRegistry(List.of(providers));
    for (AgentProvider provider : providers) {
      String id = provider.kind().id();
      registry.available.put(id, new ProviderInfo(id, null));
    }
    return registry;
  }

  /** Proveedores disponibles, con la versión de su CLI. */
  public List<ProviderInfo> available() {
    return new ArrayList<>(available.values());
  }

  /** Adaptador de un proveedor disponible en este runner. */
  public Optional<AgentProvider> get(String id) {
    return available.containsKey(id) ? Optional.of(known.get(id)) : Optional.empty();
  }

  /**
   * Entorno de los comandos y las verificaciones: el del primer proveedor conocido (Claude Code),
   * como antes de que hubiera varios.
   */
  public Map<String, String> commandEnvironment(
      Map<String, String> runnerEnv, List<String> requested) {
    return known.values().iterator().next().environment(runnerEnv, requested);
  }

  /** Nombre para mostrar de un proveedor (el id si Skynet no lo conoce). */
  public static String displayName(String id) {
    return AgentProviderKind.byId(id).map(AgentProviderKind::displayName).orElse(id);
  }
}
