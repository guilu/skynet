package dev.skynet.runner.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entorno de un agente: el runner vacía el suyo y solo pasa una lista corta de variables básicas,
 * las de credenciales de su proveedor y, de las adicionales que permite el runner ({@code
 * SKYNET_AGENT_ENV}), las que pide la política del repositorio. Así un agente de un proveedor no
 * recibe la clave de otro ni los secretos del runner.
 */
public final class AgentEnvironment {

  /** Variables que hereda cualquier agente. */
  public static final Set<String> BASIC =
      Set.of(
          "PATH",
          "HOME",
          "USER",
          "LOGNAME",
          "SHELL",
          "LANG",
          "LC_ALL",
          "LC_CTYPE",
          "TERM",
          "TMPDIR",
          "TZ",
          "HTTP_PROXY",
          "HTTPS_PROXY",
          "NO_PROXY",
          "http_proxy",
          "https_proxy",
          "no_proxy");

  private final Set<String> providerVariables;
  private final List<String> extraEnv;

  /**
   * @param providerVariables variables propias del proveedor (credenciales, configuración)
   * @param extraEnv variables adicionales que permite el runner
   */
  public AgentEnvironment(Set<String> providerVariables, List<String> extraEnv) {
    this.providerVariables = Set.copyOf(providerVariables);
    this.extraEnv = List.copyOf(extraEnv);
  }

  /** Si el agente puede recibir la variable (sin contar lo que pida la política). */
  public boolean inherits(String name) {
    return BASIC.contains(name) || providerVariables.contains(name);
  }

  /**
   * Entorno de una invocación: las básicas, las del proveedor y, de las adicionales, solo las que
   * pide la política ({@code null}: todas).
   */
  public Map<String, String> of(Map<String, String> runnerEnv, List<String> requested) {
    Map<String, String> env = new LinkedHashMap<>();
    runnerEnv.forEach(
        (name, value) -> {
          if (inherits(name)
              || (extraEnv.contains(name) && (requested == null || requested.contains(name)))) {
            env.put(name, value);
          }
        });
    return env;
  }

  /** Variables que pide la política y el runner no permite: no las recibe el agente. */
  public List<String> refused(List<String> requested) {
    return requested == null
        ? List.of()
        : requested.stream().filter(name -> !inherits(name) && !extraEnv.contains(name)).toList();
  }
}
