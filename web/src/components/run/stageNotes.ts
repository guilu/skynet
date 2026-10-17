import type { AgentFailure, Run, StageDependency, StageRun, VerificationCheck } from '../../api'

/** Por qué falló un agente, para «Falló: …». */
export const FAILURE_LABEL: Record<AgentFailure, string> = {
  lost: 'el runner se cayó',
  start: 'no llegó a arrancar',
  error: 'terminó con error',
  timeout: 'se acabó el tiempo',
  budget: 'superó el presupuesto',
  verification: 'no pasó las comprobaciones',
}

/** El último intento de cada fase, en el orden de las fases. */
export function latestAttempts(run: Run): StageRun[] {
  const latest = new Map<string, StageRun>()
  for (const stage of run.stages) {
    const seen = latest.get(stage.stageKey)
    if (!seen || stage.attempt > seen.attempt) latest.set(stage.stageKey, stage)
  }
  return [...latest.values()]
}

/** Los intentos anteriores de la fase, del primero al último. */
export function earlierAttempts(stage: StageRun, run: Run): StageRun[] {
  return run.stages
    .filter((s) => s.stageKey === stage.stageKey && s.attempt < stage.attempt)
    .sort((a, b) => a.attempt - b.attempt)
}

/** «terminó con error», o null si ningún agente del intento dice por qué falló. */
export function failureOf(stage: StageRun): string | null {
  const failure = stage.agents.findLast((a) => a.failure)?.failure
  return failure ? FAILURE_LABEL[failure] : null
}

/** «Reintenta a las 10:30:15 (intento 2 de 3)» mientras un reintento espera su hora. */
function retryNote(stage: StageRun): string | null {
  if (stage.status !== 'PENDING' || !stage.notBefore) return null
  const at = new Date(stage.notBefore).toLocaleTimeString('es-ES')
  const of = attemptOf(stage)
  return `Reintenta a las ${at} (intento ${stage.attempt}${of})`
}

/** «Tiempo máximo: 30 min · hasta las 10:30:15» mientras el intento tiene un límite. */
export function timeLimitNote(stage: StageRun): string | null {
  if (!stage.timeoutSeconds) return null
  const limit = `Tiempo máximo: ${formatSeconds(stage.timeoutSeconds)}`
  if (!stage.deadline || stage.timedOutAt) return limit
  return `${limit} · hasta las ${new Date(stage.deadline).toLocaleTimeString('es-ES')}`
}

/** 90 → «90 s», 1800 → «30 min», 7200 → «2 h». */
export function formatSeconds(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} h`
  if (seconds % 60 === 0) return `${seconds / 60} min`
  return `${seconds} s`
}

/**
 * « de 3» detrás del número de intento. Nada si la fase no se reintenta sola o ya pasó de ese
 * número: «Reintentar fase» no cuenta para `maxAttempts`.
 */
export function attemptOf(stage: StageRun): string {
  return stage.retry && stage.attempt <= stage.retry.maxAttempts
    ? ` de ${stage.retry.maxAttempts}`
    : ''
}

/** Las fases que dependen de `stageKey`, directa o indirectamente, en el orden de la ejecución. */
export function downstreamOf(stageKey: string, run: Run): string[] {
  const stages = latestAttempts(run)
  const found = new Set<string>()
  const pending = [stageKey]
  while (pending.length > 0) {
    const key = pending.pop()!
    for (const s of stages)
      if (s.dependsOn.some((d) => d.stage === key) && !found.has(s.stageKey)) {
        found.add(s.stageKey)
        pending.push(s.stageKey)
      }
  }
  return stages.map((s) => s.stageKey).filter((k) => found.has(k))
}

/**
 * «Reintentar fase» en una ejecución fallida o cancelada: qué fases vuelven a lanzarse después de
 * `stage` (las que dependen de ella y las demás que fallaron o se cancelaron), o null si no se
 * puede reintentar. Una puerta no se reintenta así, sino la fase de la que depende: también una que
 * terminó bien, si la puerta que va detrás falló.
 */
export function retryScope(stage: StageRun, run: Run): { then: string[] } | null {
  if (!['FAILED', 'CANCELLED'].includes(run.status) || run.archivedAt) return null
  if (stage.type === 'human-approval') return null
  const stages = latestAttempts(run)
  const downstream = downstreamOf(stage.stageKey, run)
  const failedGate = stages.some(
    (s) => s.type === 'human-approval' && s.status === 'FAILED' && downstream.includes(s.stageKey),
  )
  if (
    !['FAILED', 'CANCELLED'].includes(stage.status) &&
    !(stage.status === 'SUCCEEDED' && failedGate)
  )
    return null
  const then = stages
    .filter(
      (s) =>
        s.stageKey !== stage.stageKey &&
        (downstream.includes(s.stageKey) || ['FAILED', 'CANCELLED'].includes(s.status)),
    )
    .map((s) => s.stageKey)
  return { then }
}

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
  const stages = latestAttempts(run)
  const status = new Map(stages.map((s) => [s.stageKey, s.status]))
  const deps = stage.dependsOn
  const retrying = retryNote(stage)
  if (retrying) return retrying
  if (stage.cancelling) return 'Cancelando: espera a que el runner pare sus agentes'
  if (stage.status === 'FAILED' && stage.timedOutAt)
    return stage.type === 'human-approval' ? 'Caducó sin decisión' : 'Falló: se acabó el tiempo'
  if (isVerifying(stage)) return `Verificando: ${joinNames(stage.checks.map((c) => c.name))}`
  if (isCorrecting(stage)) return 'Corrigiendo con los cambios que se pidieron'
  if (stage.status === 'WAITING_FOR_APPROVAL')
    return stage.approval && stage.approval.round > 1
      ? `Espera una decisión · ronda ${stage.approval.round}`
      : 'Espera una decisión'
  if (stage.status === 'FAILED' && failedChecks(stage).length > 0)
    return `No pasó ${failedChecks(stage).join('; ')}`
  if (stage.status === 'FAILED' && failureOf(stage)) return `Falló: ${failureOf(stage)}`
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
      const failed = stages.filter((s) => s.status === 'FAILED').map((s) => s.stageKey)
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
