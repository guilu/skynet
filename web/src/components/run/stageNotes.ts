import type { Run, StageDependency, StageRun } from '../../api'

/** «a», «a y b», «a, b y c». */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names.join('')
  return `${names.slice(0, -1).join(', ')} y ${names[names.length - 1]}`
}

/** Una dependencia se cumple si su fase se completó, o si se omitió y era opcional. */
function satisfied(dep: StageDependency, status: string | undefined): boolean {
  return status === 'SUCCEEDED' || (dep.optional && status === 'SKIPPED')
}

const named = (dep: StageDependency) => (dep.optional ? `${dep.stage} (opcional)` : dep.stage)

/**
 * Qué relación tiene la fase con las demás, en una frase: a qué fases espera, por qué se omitió o
 * se canceló sin llegar a arrancar, o detrás de cuáles va. Vacía si no hay nada que contar.
 */
export function stageNote(stage: StageRun, run: Run): string {
  const status = new Map(run.stages.map((s) => [s.stageKey, s.status]))
  const deps = stage.dependsOn
  switch (stage.status) {
    case 'PENDING': {
      const waiting = deps.filter((d) => !satisfied(d, status.get(d.stage)))
      return waiting.length > 0 ? `Espera a ${joinNames(waiting.map(named))}` : ''
    }
    case 'SKIPPED': {
      const cause = deps.filter((d) => !d.optional && status.get(d.stage) === 'SKIPPED')
      return cause.length > 0
        ? `Omitida porque se omitió ${joinNames(cause.map((d) => d.stage))}`
        : ''
    }
    case 'CANCELLED': {
      // Sin agentes, la canceló la ejecución: por el fallo de otra fase o a mano.
      if (stage.agents.length > 0) break
      const failed = run.stages.filter((s) => s.status === 'FAILED').map((s) => s.stageKey)
      if (failed.length > 0) return `No arrancó porque falló ${joinNames(failed)}`
      return 'No arrancó: se canceló la ejecución'
    }
  }
  return deps.length > 0 ? `Después de ${joinNames(deps.map(named))}` : ''
}
