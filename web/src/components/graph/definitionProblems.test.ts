import { describe, expect, it } from 'vitest'
import type { Problem } from '../../api'
import {
  cycleEdges,
  missingDependencies,
  problemBadge,
  problemsByStage,
  stageIdsByIndex,
} from './definitionProblems'

const problem = (path: string, severity: Problem['severity'] = 'ERROR'): Problem => ({
  severity,
  path,
  line: 1,
  column: 1,
  message: `en ${path}`,
})

const deps = (...ids: string[]) => ids.map((stage) => ({ stage }))

describe('problemsByStage', () => {
  // La segunda fase no tiene id: el control plane se la salta, pero cuenta en la posición.
  const text = `id: x
stages:
  - id: a
    type: agent
  - type: agent
  - id: c
    type: parallel
`

  it('busca la fase por su id o, si no lo tiene, por su posición en el texto', () => {
    expect(stageIdsByIndex(text)).toEqual(['a', null, 'c'])
    const byStage = problemsByStage(text, [
      problem('stages.a.prompt'),
      problem('stages.c.children[1].agent', 'WARNING'),
      problem('stages.c'),
      problem('stages[2].type'),
      problem('stages[1].id'),
      problem('agents.dev.prompt'),
    ])
    expect([...byStage.keys()]).toEqual(['a', 'c'])
    expect(byStage.get('c')!.map((p) => p.path)).toEqual([
      'stages.c.children[1].agent',
      'stages.c',
      'stages[2].type',
    ])
  })

  it('un ciclo sale en todas sus fases, una sola vez en cada una', () => {
    const cycle = {
      ...problem('stages.build.dependsOn'),
      message: 'Las dependencias forman un ciclo: build → review → test → build',
    }
    const byStage = problemsByStage(text, [cycle, problem('stages.review.prompt')])
    expect([...byStage.keys()]).toEqual(['build', 'review', 'test'])
    expect(byStage.get('build')).toEqual([cycle])
    expect(byStage.get('review')!.map((p) => p.path)).toEqual([
      'stages.build.dependsOn',
      'stages.review.prompt',
    ])
    expect(problemBadge(byStage.get('test')!)!.label).toBe('1 error')
  })

  it('con un texto ilegible no asigna nada', () => {
    expect(problemsByStage('stages: [', [problem('stages[0]')]).size).toBe(0)
  })
})

describe('missingDependencies', () => {
  it('dice qué fases nombran cada dependencia que no existe', () => {
    const stages = [
      { id: 'a', dependsOn: deps('plan') },
      { id: 'b', dependsOn: deps('a', 'plan', 'spec') },
    ]
    expect(missingDependencies(stages)).toEqual(
      new Map([
        ['plan', ['a', 'b']],
        ['spec', ['b']],
      ]),
    )
  })
})

describe('cycleEdges', () => {
  it('marca solo las flechas de cada ciclo', () => {
    const stages = [
      { id: 'a', dependsOn: deps('c') },
      { id: 'b', dependsOn: deps('a') },
      { id: 'c', dependsOn: deps('b') },
      { id: 'd', dependsOn: deps('c', 'd', 'nada') },
      { id: 'e', dependsOn: deps('f') },
      { id: 'f', dependsOn: deps('e') },
    ]
    expect([...cycleEdges(stages)].sort()).toEqual(['a->b', 'b->c', 'c->a', 'e->f', 'f->e'])
  })

  it('sin ciclos no marca nada', () => {
    expect(
      cycleEdges([
        { id: 'a', dependsOn: [] },
        { id: 'b', dependsOn: deps('a') },
      ]).size,
    ).toBe(0)
  })
})

describe('problemBadge', () => {
  it('cuenta lo más grave', () => {
    expect(problemBadge([])).toBeNull()
    expect(problemBadge([problem('x', 'WARNING'), problem('y'), problem('z')])).toEqual({
      severity: 'ERROR',
      label: '2 errores',
    })
    expect(problemBadge([problem('x', 'UNSUPPORTED'), problem('y', 'WARNING')])).toEqual({
      severity: 'UNSUPPORTED',
      label: '1 aún no se ejecuta',
    })
    expect(problemBadge([problem('x', 'WARNING'), problem('y', 'WARNING')])?.label).toBe('2 avisos')
  })
})
