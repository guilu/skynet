import type { Run, StageDependency, StageRun, VerificationCheck } from '../../api'

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

/** Lo que comprueba un check: sus globs o su comando. */
export function checkTarget(check: VerificationCheck): string | null {
  if (check.command) return check.command
  return check.paths.length > 0 ? check.paths.join(', ') : null
}

/** Si es una puerta que corrige: se pidieron cambios y la fase `revise` los está aplicando. */
export function isCorrecting(stage: StageRun): boolean {
  return (
    stage.type === 'human-approval' &&
    stage.status === 'RUNNING' &&
    stage.approval?.status === 'CHANGES_REQUESTED'
  )
}

/** Si la fase está comprobando su worktree: su agente terminó y su verificación sigue en curso. */
export function isVerifying(stage: StageRun): boolean {
  return stage.status === 'RUNNING' && stage.verifications.some((v) => v.live)
}

export interface CheckState {
  name: string
  target: string | null
  /** `waiting`: aún no se ha comprobado; `checking`: se está comprobando. */
  state: 'waiting' | 'checking' | 'passed' | 'failed'
  /** Por qué no pasó, en cada agente que no lo pasó. */
  reasons: string[]
}

/**
 * Cada comprobación de la fase con su resultado: falla si no la pasó algún agente (en una
 * `parallel`, cada hijo se comprueba en su worktree) y pasa si la pasaron todos.
 */
export function checkStates(stage: StageRun): CheckState[] {
  const finished = stage.verifications.filter((v) => !v.live)
  const expected = stage.type === 'parallel' ? stage.agents.length : 1
  return stage.checks.map((check, i) => {
    const results = finished.map((v) => v.checks[i]).filter((c) => c != null)
    const reasons = results.filter((c) => c.passed === false).map((c) => c.reason ?? 'no pasó')
    let state: CheckState['state'] = 'waiting'
    if (reasons.length > 0) state = 'failed'
    else if (results.length > 0 && results.length >= expected && results.every((c) => c.passed))
      state = 'passed'
    else if (stage.verifications.some((v) => v.live)) state = 'checking'
    return { name: check.name, target: checkTarget(check), state, reasons }
  })
}

/** Las comprobaciones que no pasaron, «artifacts: `x` no existe», o vacío. */
export function failedChecks(stage: StageRun): string[] {
  const failed = checkStates(stage).flatMap((c) => c.reasons.map((r) => `${c.name}: ${r}`))
  if (failed.length > 0) return failed
  // Sin un check que falle, el runner no pudo comprobar el worktree.
  return stage.verifications
    .filter((v) => v.status === 'ERROR' && v.error)
    .map((v) => v.error as string)
}

/**
 * Qué relación tiene la fase con las demás, en una frase: a qué fases espera, por qué se omitió o
 * se canceló sin llegar a arrancar, o detrás de cuáles va. Vacía si no hay nada que contar.
 */
export function stageNote(stage: StageRun, run: Run): string {
  const status = new Map(run.stages.map((s) => [s.stageKey, s.status]))
  const deps = stage.dependsOn
  if (isVerifying(stage)) return `Verificando: ${joinNames(stage.checks.map((c) => c.name))}`
  if (isCorrecting(stage)) return 'Corrigiendo con los cambios que se pidieron'
  if (stage.status === 'WAITING_FOR_APPROVAL')
    return stage.approval && stage.approval.round > 1
      ? `Espera una decisión · ronda ${stage.approval.round}`
      : 'Espera una decisión'
  if (stage.status === 'FAILED' && failedChecks(stage).length > 0)
    return `No pasó ${failedChecks(stage).join('; ')}`
  switch (stage.status) {
    case 'PENDING': {
      const waiting = deps.filter((d) => !satisfied(d, status.get(d.stage)))
      return waiting.length > 0 ? `Espera a ${joinNames(waiting.map(named))}` : ''
    }
    case 'SKIPPED': {
      const cause = deps.filter((d) => !d.optional && status.get(d.stage) === 'SKIPPED')
      if (cause.length > 0)
        return `Omitida porque se omitió ${joinNames(cause.map((d) => d.stage))}`
      // Sin una dependencia omitida que la arrastre, la omitió su `condition`.
      return stage.condition
        ? `Omitida: ${stage.condition} es falsa`
        : 'Omitida porque su condición es falsa'
    }
    case 'CANCELLED': {
      // Sin agentes, la canceló la ejecución: por el fallo de otra fase o a mano.
      if (stage.agents.length > 0) break
      const failed = run.stages.filter((s) => s.status === 'FAILED').map((s) => s.stageKey)
      if (failed.length > 0) return `No arrancó porque falló ${joinNames(failed)}`
      return 'No arrancó: se canceló la ejecución'
    }
  }
  const after = deps.length > 0 ? `Después de ${joinNames(deps.map(named))}` : ''
  // Si ha llegado a arrancar, su condición se cumplió.
  if (stage.condition && stage.status !== 'READY')
    return [after, `${stage.condition} se cumplió`].filter(Boolean).join(' · ')
  return after
}
