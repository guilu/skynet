import type { FindingCategory, FindingOutcome, FindingView } from '../../api'

/** Cómo se lee cada clase de hallazgo (AP-A), en el orden de la lista del juez. */
export const CATEGORY_LABEL: Record<FindingCategory, string> = {
  tests: 'Tests',
  'caso-limite': 'Caso límite',
  errores: 'Errores',
  entradas: 'Entradas',
  secretos: 'Secretos',
  concurrencia: 'Concurrencia',
  rendimiento: 'Rendimiento',
  api: 'API',
  datos: 'Datos',
  alcance: 'Alcance',
  estilo: 'Estilo',
  documentacion: 'Documentación',
  otro: 'Otro',
}

/** Cómo se lee cada desenlace de un hallazgo (RH-A). */
export const OUTCOME_LABEL: Record<FindingOutcome, string> = {
  abierto: 'Abierto',
  refutado: 'Refutado',
  corregido: 'Corregido',
  aparcado: 'Aparcado',
  aceptado: 'Intencionado',
  irrelevante: 'No aplica',
}

/** «3 hallazgos: 1 corregido, 1 refutado, 1 aparcado». */
export function summary(findings: FindingView[]): string {
  const counts = new Map<FindingOutcome, number>()
  findings.forEach((f) => counts.set(f.outcome, (counts.get(f.outcome) ?? 0) + 1))
  const parts = (Object.keys(OUTCOME_LABEL) as FindingOutcome[])
    .filter((o) => counts.has(o))
    .map((o) => `${counts.get(o)} ${OUTCOME_LABEL[o].toLowerCase()}`)
  return `${findings.length === 1 ? '1 hallazgo' : `${findings.length} hallazgos`}: ${parts.join(', ')}`
}
