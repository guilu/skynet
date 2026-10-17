import { describe, expect, it } from 'vitest'
import type { Run, StageRun, StageStatus, VerificationResult } from '../../api'
import {
  checkStates,
  earlierAttempts,
  isCorrecting,
  joinNames,
  latestAttempts,
  stageNote,
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
  }
}

const run = (...stages: StageRun[]) => ({ stages }) as Run

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
          },
          { name: 'diff', target: null, passed: true, reason: null },
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
})
