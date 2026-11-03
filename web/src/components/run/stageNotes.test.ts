import { describe, expect, it } from 'vitest'
import type { Run, StageJudge, StageRun, StageStatus, VerificationResult } from '../../api'
import {
  checkStates,
  earlierAttempts,
  fallbackNote,
  isCorrecting,
  joinNames,
  judgeNote,
  latestAttempts,
  quotaNote,
  retryScope,
  stageNote,
  timeLimitNote,
} from './stageNotes'

function stage(
  stageKey: string,
  status: StageStatus,
  dependsOn: string[] = [],
  agents = 0,
  condition: string | null = null,
): StageRun {
  return {
    id: stageKey,
    stageKey,
    name: null,
    agent: null,
    dependsOn: dependsOn.map((d) => ({ stage: d.replace('?', ''), optional: d.endsWith('?') })),
    type: 'agent',
    command: null,
    condition,
    status,
    attempt: 1,
    startedAt: null,
    finishedAt: null,
    agents: Array.from({ length: agents }, () => ({}) as StageRun['agents'][number]),
    checks: [],
    reads: [],
    verifications: [],
    approval: null,
    retry: null,
    notBefore: null,
    timeoutSeconds: null,
    deadline: null,
    timedOutAt: null,
    cancelling: false,
    retriedBy: null,
    children: [],
    phase: null,
    quotaWait: null,
    judge: null,
  }
}

const run = (...stages: StageRun[]) => ({ stages }) as Run

describe('quotaNote', () => {
  const now = new Date('2026-11-02T13:00:00Z').getTime()
  const waiting = (resetsAt: string | null, notBefore: string): StageRun => ({
    ...stage('implement', 'PENDING'),
    attempt: 2,
    notBefore,
    quotaWait: { provider: 'claude-code', window: 'five_hour', resetsAt },
  })

  it('dice de quién es la cuota, cuándo se renueva y qué ventana', () => {
    const note = quotaNote(waiting('2026-11-02T14:04:00Z', '2026-11-02T14:04:45Z'), now)
    expect(note).toMatch(
      /^Esperando la cuota de Claude Code · se renueva a las \d\d:04 \(ventana de 5 h\)$/,
    )
  })

  it('sin hora de renovación, dice cuándo vuelve a probar', () => {
    expect(quotaNote(waiting(null, '2026-11-02T13:30:00Z'), now)).toMatch(
      /^Esperando la cuota de Claude Code · vuelve a probar a las \d\d:30$/,
    )
  })

  it('ya pasada la hora, o sin espera, no dice nada', () => {
    expect(quotaNote(waiting('2026-11-02T12:00:00Z', '2026-11-02T12:00:30Z'), now)).toBeNull()
    expect(quotaNote(stage('implement', 'PENDING'), now)).toBeNull()
  })
})

describe('fallbackNote', () => {
  const now = new Date('2026-11-02T13:00:00Z').getTime()
  type Agent = StageRun['agents'][number]
  const withAgents = (status: StageStatus, ...agents: Partial<Agent>[]): StageRun => ({
    ...stage('implement', status),
    agents: agents.map((a) => ({ provider: 'claude-code', fallback: null, ...a }) as Agent),
  })
  const codex: Partial<Agent> = {
    provider: 'codex',
    fallback: { provider: 'claude-code', window: 'five_hour', resetsAt: '2026-11-02T15:04:00Z' },
  }

  it('dice quién sustituye a quién y hasta cuándo no tiene cuota', () => {
    expect(fallbackNote(withAgents('RUNNING', {}, codex), now)).toMatch(
      /^Sustituido por Codex \(Claude Code sin cuota hasta las \d\d:04\)$/,
    )
  })

  it('sale en la nota de la fase mientras sigue', () => {
    const s = withAgents('RUNNING', {}, codex)
    expect(stageNote(s, run(s))).toMatch(/^Sustituido por Codex/)
  })

  it('si el último agente vuelve al principal, o no hay reserva, no dice nada', () => {
    expect(fallbackNote(withAgents('RUNNING', {}, codex, {}), now)).toBeNull()
    expect(fallbackNote(withAgents('RUNNING', {}), now)).toBeNull()
  })
})

describe('stageNote', () => {
  it('une nombres en castellano', () => {
    expect(joinNames(['a'])).toBe('a')
    expect(joinNames(['a', 'b'])).toBe('a y b')
    expect(joinNames(['a', 'b', 'c'])).toBe('a, b y c')
  })

  it('una fase pendiente dice a qué fases sin terminar espera', () => {
    const plan = stage('plan', 'SUCCEEDED')
    const tests = stage('tests', 'RUNNING', [], 1)
    const fix = stage('fix', 'PENDING', ['plan?', 'tests'])
    expect(stageNote(fix, run(plan, tests, fix))).toBe('Espera a tests')
    const pendingPlan = stage('plan', 'RUNNING', [], 1)
    expect(stageNote(fix, run(pendingPlan, tests, fix))).toBe('Espera a plan (opcional) y tests')
  })

  it('una puerta dice si espera una decisión o está corrigiendo', () => {
    const plan = stage('plan', 'SUCCEEDED')
    const approval = {
      round: 2,
      status: 'PENDING',
      correctionAgentRunId: null,
    } as unknown as StageRun['approval']
    const gate = {
      ...stage('aprobar-plan', 'WAITING_FOR_APPROVAL', ['plan']),
      type: 'human-approval',
      approval,
    }
    expect(stageNote(gate, run(plan, gate))).toBe('Espera una decisión · ronda 2')
    const correcting = {
      ...gate,
      status: 'RUNNING' as StageStatus,
      approval: { ...approval!, status: 'CHANGES_REQUESTED' as const, correctionAgentRunId: 'c' },
    }
    expect(isCorrecting(correcting)).toBe(true)
    expect(stageNote(correcting, run(plan, correcting))).toBe(
      'Corrigiendo con los cambios que se pidieron',
    )
  })

  it('cada fase se ve con su último intento, y el reintento dice cuándo arranca', () => {
    const failed = {
      ...stage('implement', 'FAILED', [], 1),
      agents: [{ id: 'a1', failure: 'error' } as StageRun['agents'][number]],
    }
    const retry = {
      ...stage('implement', 'PENDING'),
      id: 'implement-2',
      attempt: 2,
      notBefore: '2026-10-17T10:00:30Z',
      retry: { maxAttempts: 3, on: ['error' as const], delaySeconds: 30 },
    }
    const review = stage('review', 'PENDING', ['implement'])
    const all = run(failed, review, retry)
    expect(latestAttempts(all).map((s) => s.id)).toEqual(['implement-2', 'review'])
    expect(earlierAttempts(retry, all)).toEqual([failed])
    const at = new Date('2026-10-17T10:00:30Z').toLocaleTimeString('es-ES')
    expect(stageNote(retry, all)).toBe(`Reintenta a las ${at} (intento 2 de 3)`)
    // La que depende espera al último intento, no al que falló.
    expect(stageNote(review, all)).toBe('Espera a implement')
    expect(stageNote(failed, run(failed, review))).toBe('Falló: terminó con error')
  })

  it('una opcional omitida no hace esperar', () => {
    const plan = stage('plan', 'SKIPPED')
    const fix = stage('fix', 'PENDING', ['plan?'])
    expect(stageNote(fix, run(plan, fix))).toBe('')
  })

  it('una fase omitida en cascada dice cuál la arrastró', () => {
    const build = stage('build', 'SKIPPED')
    const ship = stage('ship', 'SKIPPED', ['build'])
    expect(stageNote(ship, run(build, ship))).toBe('Omitida porque se omitió build')
  })

  it('una fase omitida sin cascada la omitió su condición', () => {
    const review = stage('review', 'SUCCEEDED', [], 1)
    const fix = stage('fix', 'SKIPPED', ['review'])
    expect(stageNote(fix, run(review, fix))).toBe('Omitida porque su condición es falsa')
    const conditional = stage('fix', 'SKIPPED', ['review'], 0, 'review.hasBlockingFindings')
    expect(stageNote(conditional, run(review, conditional))).toBe(
      'Omitida: review.hasBlockingFindings es falsa',
    )
  })

  it('una fase con condición que arrancó dice que se cumplió', () => {
    const review = stage('review', 'SUCCEEDED', [], 1)
    const fix = stage('fix', 'RUNNING', ['review'], 1, 'review.hasBlockingFindings')
    expect(stageNote(fix, run(review, fix))).toBe(
      'Después de review · review.hasBlockingFindings se cumplió',
    )
    const waiting = stage('fix', 'PENDING', ['review'], 0, 'review.hasBlockingFindings')
    expect(stageNote(waiting, run(review, waiting))).toBe('')
  })

  it('una fase cancelada sin arrancar dice qué fase falló', () => {
    const a = stage('a', 'FAILED', [], 1)
    const b = stage('b', 'CANCELLED', [], 1)
    const c = stage('c', 'CANCELLED', ['a', 'b'])
    expect(stageNote(c, run(a, b, c))).toBe('No arrancó porque falló a')
    expect(stageNote(b, run(a, b, c))).toBe('')
    expect(stageNote(stage('x', 'CANCELLED'), run(stage('x', 'CANCELLED')))).toBe(
      'No arrancó: se canceló la ejecución',
    )
  })

  it('una fase en marcha o terminada dice detrás de cuáles va', () => {
    const fix = stage('fix', 'SUCCEEDED', ['plan', 'tests'], 1)
    expect(stageNote(fix, run(fix))).toBe('Después de plan y tests')
    expect(stageNote(stage('solo', 'RUNNING', [], 1), run())).toBe('')
  })

  it('una fase con comprobaciones dice que verifica y, si falla, qué no pasó', () => {
    const verification = (
      live: boolean,
      checks: VerificationResult['checks'],
    ): VerificationResult =>
      ({ live, status: live ? 'RUNNING' : 'FAILED', checks, error: null }) as VerificationResult
    const spec: StageRun = {
      ...stage('spec', 'RUNNING', [], 1),
      checks: [
        { name: 'artifacts', command: null, paths: ['docs/spec.md'] },
        { name: 'diff', command: null, paths: [] },
      ],
    }
    expect(stageNote(spec, run(spec))).toBe('')
    expect(checkStates(spec).map((c) => c.state)).toEqual(['waiting', 'waiting'])

    const verifying = { ...spec, verifications: [verification(true, [])] }
    expect(checkStates(verifying).map((c) => c.state)).toEqual(['checking', 'checking'])
    expect(stageNote(verifying, run(verifying))).toBe('Verificando: artifacts y diff')

    const failed: StageRun = {
      ...spec,
      status: 'FAILED',
      verifications: [
        verification(false, [
          {
            name: 'artifacts',
            target: 'docs/spec.md',
            passed: false,
            reason: '`docs/spec.md` no existe',
            omitted: [],
            warnings: [],
          },
          { name: 'diff', target: null, passed: true, reason: null, omitted: [], warnings: [] },
        ]),
      ],
    }
    expect(stageNote(failed, run(failed))).toBe('No pasó artifacts: `docs/spec.md` no existe')
    expect(checkStates(failed)).toEqual([
      {
        name: 'artifacts',
        target: 'docs/spec.md',
        state: 'failed',
        reasons: ['`docs/spec.md` no existe'],
      },
      { name: 'diff', target: null, state: 'passed', reasons: [] },
    ])
  })

  it('el límite de tiempo, la caducidad y «Cancelando»', () => {
    const limited = { ...stage('implement', 'RUNNING', [], 1), timeoutSeconds: 1800 }
    expect(timeLimitNote(limited)).toBe('Tiempo máximo: 30 min')
    const deadline = '2026-10-17T10:30:00Z'
    const at = new Date(deadline).toLocaleTimeString('es-ES')
    expect(timeLimitNote({ ...limited, deadline })).toBe(`Tiempo máximo: 30 min · hasta las ${at}`)
    expect(timeLimitNote(stage('tests', 'RUNNING'))).toBeNull()

    const stopping = { ...limited, cancelling: true }
    expect(stageNote(stopping, run(stopping))).toBe(
      'Cancelando: espera a que el runner pare sus agentes',
    )
    const timedOut = { ...limited, status: 'FAILED' as const, timedOutAt: deadline }
    expect(stageNote(timedOut, run(timedOut))).toBe('Falló: se acabó el tiempo')
    const gate = { ...timedOut, type: 'human-approval', agents: [] }
    expect(stageNote(gate, run(gate))).toBe('Caducó sin decisión')
  })

  it('«Reintentar fase» dice qué vuelve a lanzarse y no sale en una puerta', () => {
    const plan = stage('plan', 'SUCCEEDED', [], 1)
    const gate = { ...stage('aprobar', 'FAILED', ['plan']), type: 'human-approval' }
    const implement = stage('implement', 'CANCELLED', ['aprobar'])
    const lint = stage('lint', 'SUCCEEDED', [], 1)
    const failed = { ...run(plan, gate, implement, lint), status: 'FAILED' } as Run
    // El plan que rechazó la puerta se repite, con la puerta y lo que depende de ella.
    expect(retryScope(plan, failed)).toEqual({ then: ['aprobar', 'implement'] })
    expect(retryScope(gate, failed)).toBeNull()
    // Una fase que terminó bien sin una puerta fallida detrás no se ofrece.
    expect(retryScope(lint, failed)).toBeNull()
    // Solo en una ejecución que ha terminado mal.
    expect(retryScope(plan, { ...failed, status: 'RUNNING' } as Run)).toBeNull()
    const broken = { ...stage('tests', 'FAILED', [], 1) }
    expect(retryScope(broken, { ...run(broken, implement), status: 'FAILED' } as Run)).toEqual({
      then: ['implement'],
    })
  })
})

describe('judgeNote', () => {
  const judge = (over: Partial<StageJudge>): StageJudge => ({
    status: 'JUDGING',
    round: 1,
    shownRound: 1,
    maxRounds: 2,
    fix: 'implementar',
    threshold: 3.5,
    score: null,
    passed: null,
    approvals: null,
    judges: null,
    findings: [],
    judgements: [],
    error: null,
    ...over,
  })
  const judged = (j: StageJudge, status: StageStatus = 'RUNNING') => ({
    ...stage('juzgar', status),
    type: 'verification',
    judge: j,
  })

  it('dice qué hace el juez en cada momento', () => {
    expect(judgeNote(judged(judge({ status: 'CHECKING' })))).toBe('Comprobando antes de juzgar')
    expect(judgeNote(judged(judge({ round: 2 })))).toBe('Juzgando: 1 juez (ronda 2)')
    expect(judgeNote(judged(judge({ status: 'FIXING' })))).toBe(
      'implementar corrige lo que suspendió el juez (ronda 1 de 2)',
    )
  })

  it('al terminar, la nota de la fase', () => {
    const passed = judged(
      judge({ status: 'PASSED', score: 3.8, passed: true, approvals: 2, judges: 2 }),
      'SUCCEEDED',
    )
    expect(judgeNote(passed)).toBe('3,8 / 5 · aprobado por 2 de 2')
    expect(stageNote(passed, run(passed))).toBe('3,8 / 5 · aprobado por 2 de 2')
    const failed = judged(
      judge({
        status: 'FAILED',
        score: 2.5,
        passed: false,
        approvals: 0,
        judges: 1,
        error: 'Suspende',
      }),
      'FAILED',
    )
    expect(judgeNote(failed)).toBe('No pasó: Suspende')
  })

  it('sin juez no dice nada', () => {
    expect(judgeNote(stage('a', 'RUNNING'))).toBeNull()
  })
})
