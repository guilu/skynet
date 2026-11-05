import { useQuery } from '@tanstack/react-query'
import { api, type Run } from '../../api'
import type { StatusTone } from '../../format'

/** Los intentos de un trabajo, del primero al último: el número de cada uno es su posición. */
export function inOrder(runs: Run[]): Run[] {
  return [...runs].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))
}

/**
 * El número de intento de una ejecución dentro de su trabajo (FS-E): «Intento 3 de DEMO-4». Usa
 * la misma consulta que la página del trabajo, así que no cuesta peticiones de más al volver.
 */
export function useAttempt(run: Pick<Run, 'id' | 'workItemId'> | undefined) {
  const runs = useQuery({
    queryKey: ['runs', run?.workItemId, false],
    queryFn: () => api.runs(run!.workItemId),
    enabled: !!run,
  })
  if (!run || !runs.data) return null
  const index = inOrder(runs.data).findIndex((r) => r.id === run.id)
  return index < 0 ? null : { number: index + 1, total: runs.data.length }
}

/** Milisegundos que trabajó un intento (hasta ahora, si sigue). */
export function runMillis(run: Run, now: number): number | null {
  if (!run.startedAt) return null
  const end = run.finishedAt ? Date.parse(run.finishedAt) : now
  return Math.max(0, end - Date.parse(run.startedAt))
}

/** La fase en la que falló un intento, si la hay. */
export function failedStage(run: Run): string | null {
  const stage = run.stages.find((s) => s.status === 'FAILED')
  return stage ? (stage.name ?? stage.stageKey) : null
}

/** Estado de un intento en palabras: «Esperando aprobación», «Fallido en Verificación»… */
export function attemptStatus(run: Run): { label: string; tone: StatusTone } {
  const waiting = run.stages.find((s) => s.status === 'WAITING_FOR_APPROVAL')
  if (run.status === 'RUNNING' && waiting) return { label: 'Esperando aprobación', tone: 'warn' }
  if (run.cancelRequestedAt && !run.finishedAt) return { label: 'Cancelando', tone: 'warn' }
  switch (run.status) {
    case 'PENDING':
      return { label: 'En cola', tone: 'neutral' }
    case 'RUNNING':
      return { label: 'En marcha', tone: 'active' }
    case 'SUCCEEDED':
      return { label: 'Completado', tone: 'ok' }
    case 'CANCELLED':
      return { label: 'Cancelado', tone: 'neutral' }
    case 'FAILED': {
      const stage = failedStage(run)
      return { label: stage ? `Fallido en ${stage}` : 'Fallido', tone: 'bad' }
    }
  }
}
