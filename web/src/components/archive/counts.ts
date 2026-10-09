import type { DeletionCounts } from '../../api'
import { formatBytes } from '../../format'

/** Partes de la vista previa que se enseñan, en orden; las que valen 0 no salen. */
const COUNT_LABELS: [keyof DeletionCounts, string, string][] = [
  ['repositories', 'repositorio', 'repositorios'],
  ['workItems', 'trabajo', 'trabajos'],
  ['runs', 'ejecución', 'ejecuciones'],
  ['agents', 'agente', 'agentes'],
  ['artifacts', 'artefacto', 'artefactos'],
  ['events', 'evento', 'eventos'],
]

/** Resumen de lo que se borra: «1 ejecución, 2 agentes, 3 artefactos (12 KB)». */
export function describeCounts(counts: DeletionCounts): string {
  const parts = COUNT_LABELS.filter(([key]) => counts[key] > 0).map(([key, one, many]) => {
    const n = counts[key]
    const text = `${n} ${n === 1 ? one : many}`
    return key === 'artifacts' && counts.artifactBytes > 0
      ? `${text} (${formatBytes(counts.artifactBytes)})`
      : text
  })
  return parts.length > 0 ? parts.join(', ') : 'Nada más que el propio registro'
}
