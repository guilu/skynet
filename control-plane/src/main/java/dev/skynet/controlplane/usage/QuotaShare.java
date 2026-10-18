package dev.skynet.controlplane.usage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La parte de una ventana de cuota que se atribuye a un agente o a una ejecución.
 *
 * @param bucket grupo de modelos ({@code default} en Claude Code y Codex)
 * @param window {@code five_hour} o {@code seven_day}
 * @param share parte de la ventana, de 0 a 1 (0,02 es el 2 % de la ventana)
 * @param method {@code measured} si todo se midió con el agente solo, {@code prorated} si alguna
 *     parte se repartió con otros agentes o con el uso de fuera de Skynet
 */
public record QuotaShare(
    String provider, String bucket, String window, double share, String method) {

  /** Suma las partes de cada CLI, grupo y ventana; es {@code prorated} si alguna lo es. */
  public static List<QuotaShare> sum(Collection<QuotaShare> shares) {
    Map<List<String>, QuotaShare> total = new LinkedHashMap<>();
    for (QuotaShare s : shares) {
      total.merge(
          List.of(s.provider(), s.bucket(), s.window()),
          s,
          (a, b) ->
              new QuotaShare(
                  a.provider(),
                  a.bucket(),
                  a.window(),
                  a.share() + b.share(),
                  "measured".equals(a.method()) && "measured".equals(b.method())
                      ? "measured"
                      : "prorated"));
    }
    return new ArrayList<>(total.values());
  }
}
