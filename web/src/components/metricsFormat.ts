import type { MetricsBucket, RunMetrics } from '../api'

const hourLabel = new Intl.DateTimeFormat('es-ES', { hour: '2-digit', minute: '2-digit' })
const dayLabel = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short' })
const longLabel = new Intl.DateTimeFormat('es-ES', { dateStyle: 'medium', timeStyle: 'short' })

/** Etiqueta corta del eje: la hora o el día del tramo. */
export function bucketLabel(bucket: MetricsBucket, unit: RunMetrics['bucket']) {
  const date = new Date(bucket.start)
  return unit === 'hour' ? hourLabel.format(date) : dayLabel.format(date)
}

/** Título completo del tramo, para el tooltip y la tabla. */
export function bucketTitle(bucket: MetricsBucket, unit: RunMetrics['bucket']) {
  const date = new Date(bucket.start)
  return unit === 'hour' ? longLabel.format(date) : dayLabel.format(date)
}
