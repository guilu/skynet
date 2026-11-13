// Lo que cuenta Skyny en vivo en la banda de una ejecución (SV-B): el contador de fases y el
// letrero de un reintento.
import type { Run, StageRun } from '../../api'
import { retryMessage } from './three/transitions'

export function runLive(
  run: Pick<Run, 'status' | 'currentStageRunId'>,
  stages: Pick<StageRun, 'id' | 'attempt' | 'retry'>[],
): { progress: [number, number] | null; message?: string } {
  const index = stages.findIndex((s) => s.id === run.currentStageRunId)
  const stage = index >= 0 ? stages[index] : undefined
  // Con varias fases, el contador dice por cuál va (2/4); con una sola no aporta nada.
  const progress: [number, number] | null =
    run.status === 'RUNNING' && stage && stages.length > 1 ? [index + 1, stages.length] : null
  let message: string | undefined
  if (run.status === 'RUNNING' && stage && stage.attempt > 1)
    message =
      stage.retry && stage.retry.maxAttempts > 1
        ? retryMessage(stage.attempt - 1, stage.retry.maxAttempts - 1)
        : `INTENTO ${stage.attempt}`
  return { progress, message }
}
