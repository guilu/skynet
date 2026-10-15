import { describe, expect, it } from 'vitest'
import type { Run, StageRun, StageStatus, VerificationResult } from '../../api'
import { checkStates, joinNames, stageNote } from './stageNotes'

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
