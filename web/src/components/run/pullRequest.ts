import type { Run } from '../../api'

/** Las fases de entrega por PR de una ejecución: si no tiene ninguna, no hay tarjeta de la PR. */
export function hasPullRequestStages(run: Run): boolean {
  return run.stages.some((s) => s.type === 'github-pr')
}

/** La clave de las consultas de la tarjeta de la PR: los eventos `*.github.*` la releen. */
export const pullRequestKey = (runId: string) => ['pull-request', runId] as const
