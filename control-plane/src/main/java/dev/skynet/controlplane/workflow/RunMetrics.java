package dev.skynet.controlplane.workflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Métricas de las ejecuciones creadas en un periodo. Los tokens y el coste son la suma de sus
 * agentes; {@code null} si ninguno los ha informado.
 *
 * @param bucket {@code hour} o {@code day}: el tamaño de cada tramo de {@code buckets}
 * @param active ejecuciones aún sin terminar
 * @param medianDurationSeconds duración mediana de las terminadas, o {@code null} si no hay
 * @param buckets ejecuciones y coste por tramo, del más antiguo al más reciente, también los vacíos
 */
public record RunMetrics(
    Instant since,
    Instant until,
    String bucket,
    long total,
    long active,
    long succeeded,
    long failed,
    long cancelled,
    Double medianDurationSeconds,
    Long inputTokens,
    Long outputTokens,
    BigDecimal costUsd,
    List<Bucket> buckets) {

  /** Un tramo: empieza en {@code start} (en la zona pedida) y dura una hora o un día. */
  public record Bucket(
      Instant start, long total, long succeeded, long failed, long cancelled, BigDecimal costUsd) {}
}
