import type {
  AgentFailure,
  JudgeLens,
  Run,
  StageDependency,
  StageRun,
  VerificationCheck,
} from '../../api'
import { providerName } from '../../format'

/** Por qué falló un agente, para «Falló: …». */
export const FAILURE_LABEL: Record<AgentFailure, string> = {
  lost: 'el runner se cayó',
  start: 'no llegó a arrancar',
  error: 'terminó con error',
  timeout: 'se acabó el tiempo',
  budget: 'superó el presupuesto',
  verification: 'no pasó las comprobaciones',
  quota: 'se quedó sin cuota',
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

/** Si la fase espera ahora a que se renueve la cuota de su proveedor (CQ-A). */
export function waitsForQuota(stage: StageRun, now: number = Date.now()): boolean {
  return (
    !!stage.quotaWait &&
    (stage.status === 'PENDING' || stage.status === 'READY') &&
    !!stage.notBefore &&
    new Date(stage.notBefore).getTime() > now
  )
}

const WINDOW_LABEL = { five_hour: 'ventana de 5 h', seven_day: 'ventana semanal' } as const

/** «15:04», o «jue 6, 10:00» si no es hoy. */
function shortTime(iso: string, now: number): string {
  const at = new Date(iso)
  const today = at.toDateString() === new Date(now).toDateString()
  return today
    ? at.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' })
    : at.toLocaleString('es-ES', {
        weekday: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
}

/**
 * «Esperando la cuota de Claude Code · se renueva a las 15:04 (ventana de 5 h)» mientras la fase
 * espera a la cuota; sin hora de renovación, cuándo vuelve a probar.
 */
export function quotaNote(stage: StageRun, now: number = Date.now()): string | null {
  if (!waitsForQuota(stage, now)) return null
  const wait = stage.quotaWait!
  const who = `Esperando la cuota de ${providerName(wait.provider)}`
  const window = wait.window ? ` (${WINDOW_LABEL[wait.window]})` : ''
  if (wait.resetsAt) return `${who} · se renueva a las ${shortTime(wait.resetsAt, now)}${window}`
  return `${who} · vuelve a probar a las ${shortTime(stage.notBefore!, now)}`
}

/**
 * «Sustituido por Codex (Claude Code sin cuota hasta las 15:04)» si el último agente de la fase es
 * la reserva de un proveedor sin cuota (CQ-B).
 */
export function fallbackNote(stage: StageRun, now: number = Date.now()): string | null {
  const agent = stage.agents.findLast((a) => a.fallback)
  if (!agent?.fallback || agent !== stage.agents.at(-1)) return null
  const from = providerName(agent.fallback.provider)
  const until = agent.fallback.resetsAt
    ? ` hasta las ${shortTime(agent.fallback.resetsAt, now)}`
    : ''
  return `Sustituido por ${providerName(agent.provider)} (${from} sin cuota${until})`
}

/** «Reintenta a las 10:30:15 (intento 2 de 3)» mientras un reintento espera su hora. */
function retryNote(stage: StageRun): string | null {
  const quota = quotaNote(stage)
  if (quota) return quota
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

/** Cómo se nombra cada lente de un juez. */
export const LENS_LABEL: Record<JudgeLens, string> = {
  correctness: 'corrección',
  security: 'seguridad',
  performance: 'rendimiento',
  reproduce: 'reproducir',
  completeness: 'completitud',
}

/** 3.8 → «3,8», 4 → «4», 3.75 → «3,75». */
export function formatScore(score: number): string {
  return score.toLocaleString('es-ES', { maximumFractionDigits: 2 })
}

/** «3,8 / 5 · aprobado por 2 de 2» o «2,5 / 5 · suspenso: aprueba 0 de 1»; null sin veredicto. */
export function judgeScore(stage: StageRun): string | null {
  const judge = stage.judge
  if (!judge || judge.score == null) return null
  const approvals = judge.approvals ?? 0
  const judges = judge.judges ?? 0
  return judge.passed
    ? `${formatScore(judge.score)} / 5 · aprobado por ${approvals} de ${judges}`
    : `${formatScore(judge.score)} / 5 · suspenso: aprueba ${approvals} de ${judges}`
}

/**
 * Lo que hace el juez de la fase ahora (VA-B): comprobando, juzgando, corrigiendo con la fase de
 * `fix` o su nota al terminar; null si la fase no tiene juez.
 */
export function judgeNote(stage: StageRun): string | null {
  const judge = stage.judge
  if (!judge) return null
  const round = judge.round > 1 ? ` (ronda ${judge.round})` : ''
  if (judge.guide) {
    switch (judge.status) {
      case 'CHECKING':
        return stage.status === 'RUNNING' ? `Comprobando antes de la guía${round}` : null
      case 'JUDGING':
      case 'REFUTING':
        return `Ejecutando la guía de pruebas${round}`
      case 'FIXING':
        return `${judge.fix} corrige lo que falla de la guía (ronda ${judge.round} de ${judge.maxRounds})`
      case 'PASSED':
        return judge.guide.note ?? 'Pasa la guía'
      case 'FAILED':
        return judge.error ? `No pasó: ${judge.error}` : judge.guide.note
    }
  }
  switch (judge.status) {
    case 'CHECKING':
      return stage.status === 'RUNNING' ? `Comprobando antes de juzgar${round}` : null
    case 'JUDGING': {
      const n = judge.judgements.length || 1
      return `Juzgando: ${n === 1 ? '1 juez' : `${n} jueces`}${round}`
    }
    case 'REFUTING': {
      const n = judge.refuting
      return `Refutando ${n === 1 ? '1 hallazgo que bloquea' : `${n} hallazgos que bloquean`}${round}`
    }
    case 'FIXING':
      return `${judge.fix} corrige lo que suspendió el juez (ronda ${judge.round} de ${judge.maxRounds})`
    case 'PASSED':
      return judgeScore(stage)
    case 'FAILED':
      return judge.error ? `No pasó: ${judge.error}` : judgeScore(stage)
  }
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
  // Con juez, cada ronda vuelve a comprobar: cuenta la última (VA-B).
  const verifications = stage.judge ? stage.verifications.slice(-1) : stage.verifications
  const finished = verifications.filter((v) => !v.live)
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
  const fallback = fallbackNote(stage)
  if (fallback && stage.status !== 'FAILED' && stage.status !== 'CANCELLED') return fallback
  if (stage.cancelling) return 'Cancelando: espera a que el runner pare sus agentes'
  const judging = judgeNote(stage)
  if (judging && !(stage.status === 'FAILED' && stage.timedOutAt)) return judging
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
