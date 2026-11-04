import type { WorkItemSummary, WorkPhase } from '../../api'
import type { StatusTone } from '../../format'

/**
 * Las fases de un trabajo (FS-B las calcula el servidor) con el nombre y el tono con que se ven,
 * en el orden en que se recorren: primero lo que te necesita.
 */
export const PHASES: { id: WorkPhase; label: string; tone: StatusTone }[] = [
  { id: 'WAITING', label: 'Te necesita', tone: 'warn' },
  { id: 'STUCK', label: 'Atascado', tone: 'bad' },
  { id: 'RUNNING', label: 'En marcha', tone: 'active' },
  { id: 'REVIEW', label: 'PR en revisión', tone: 'active' },
  { id: 'TODO', label: 'Por empezar', tone: 'neutral' },
  { id: 'DONE', label: 'Entregado', tone: 'ok' },
]

export const phaseInfo = (phase: WorkPhase) => PHASES.find((p) => p.id === phase) ?? PHASES[4]

/** Primero lo que te necesita y lo atascado; dentro de cada fase, lo más reciente. */
export function byPhase(a: WorkItemSummary, b: WorkItemSummary) {
  const order = (p: WorkPhase) => PHASES.findIndex((x) => x.id === p)
  return (
    order(a.phase) - order(b.phase) || Date.parse(b.lastActivityAt) - Date.parse(a.lastActivityAt)
  )
}

/** Importe con dos decimales, como en el boceto: «2,14 US$». */
export function formatUsd(usd: number | null): string {
  if (usd == null) return '—'
  return `${usd.toLocaleString('es-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} US$`
}

/** «3 intentos · 2,14 US$» o «sin empezar». */
export function attemptsLine(w: Pick<WorkItemSummary, 'attempts' | 'costUsd'>): string {
  if (w.attempts === 0) return 'sin empezar'
  const n = `${w.attempts} ${w.attempts === 1 ? 'intento' : 'intentos'}`
  return w.costUsd == null ? n : `${n} · ${formatUsd(w.costUsd)}`
}
