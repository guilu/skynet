import { Check, Hand, Play, SkipForward, Square, X, type LucideIcon } from 'lucide-react'
import type { StageRun } from '../../api'
import { formatDuration, statusLabel } from '../../format'
import { spanDuration, type Span } from './runSpans'
import { isCorrecting, isVerifying } from './stageNotes'

export const ACTIVE = ['READY', 'STARTING', 'RUNNING']
export const WAITING = ['WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL']

/** Aspecto de una fase, en el camino y en el grafo: hecha, en curso, esperando, fallida, omitida… */
export function stepState(status: string): { className: string; icon: LucideIcon | null } {
  if (status === 'SUCCEEDED') return { className: 'done', icon: Check }
  if (ACTIVE.includes(status)) return { className: 'now', icon: Play }
  if (WAITING.includes(status)) return { className: 'hold', icon: Hand }
  if (status === 'FAILED') return { className: 'fail', icon: X }
  if (status === 'SKIPPED') return { className: 'skip', icon: SkipForward }
  if (status === 'CANCELLED') return { className: 'skip', icon: Square }
  return { className: 'todo', icon: null }
}

/**
 * Lo que hace la fase ahora, en pocas palabras: «Cancelando», «Verificando», la herramienta en
 * curso o, si no, cuánto lleva (o duró) o su estado.
 */
export function stageActivity(stage: StageRun, span: Span | undefined, now: number): string {
  if (stage.cancelling) return 'Cancelando'
  if (isVerifying(stage)) return 'Verificando'
  if (isCorrecting(stage)) return 'Corrigiendo'
  if (stage.status === 'WAITING_FOR_APPROVAL') return 'Esperando aprobación'
  const running = stage.agents.find((a) => a.currentTool && !a.finishedAt)
  if (running?.currentTool) return running.currentTool
  if (span && span.start != null && stage.status !== 'SKIPPED')
    return formatDuration(spanDuration(span, now))
  return statusLabel(stage.status)
}
