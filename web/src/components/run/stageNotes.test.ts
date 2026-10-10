import { describe, expect, it } from 'vitest'
import type { Run, StageRun, StageStatus } from '../../api'
import { joinNames, stageNote } from './stageNotes'

function stage(
  stageKey: string,
  status: StageStatus,
  dependsOn: string[] = [],
  agents = 0,
): StageRun {
  return {
    id: stageKey,
    stageKey,
    name: null,
    agent: null,
    dependsOn: dependsOn.map((d) => ({ stage: d.replace('?', ''), optional: d.endsWith('?') })),
    status,
    attempt: 1,
    startedAt: null,
    finishedAt: null,
    agents: Array.from({ length: agents }, () => ({}) as StageRun['agents'][number]),
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
})
