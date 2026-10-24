import { describe, expect, it } from 'vitest'
import type { StageDefinition } from '../../api'
import { stageDetail, stageHints, toGraphStages } from './definitionNodes'
import type { Phase } from '../../api'
import {
  GROUP_HEADER,
  LANE_HEADER,
  lanesOf,
  layoutStages,
  NODE_HEIGHT,
  type GraphStage,
} from './layout'

const stage = (id: string, dependsOn: string[] = [], children: string[] = []): GraphStage => ({
  id,
  dependsOn: dependsOn.map((d) => ({ stage: d.replace('?', ''), optional: d.endsWith('?') })),
  children,
})

const phased = (id: string, phase: Phase | null, dependsOn: string[] = []): GraphStage => ({
  ...stage(id, dependsOn),
  phase,
})

describe('layoutStages', () => {
  it('coloca cada fase a la derecha de las que espera', () => {
    const layout = layoutStages([
      stage('plan'),
      stage('left', ['plan']),
      stage('right', ['plan']),
      stage('merge', ['left', 'right']),
    ])
    const x = Object.fromEntries(layout.stages.map((s) => [s.id, s.x]))
    expect(x.left).toBeGreaterThan(x.plan)
    expect(x.right).toBe(x.left)
    expect(x.merge).toBeGreaterThan(x.left)
    const [left, right] = layout.stages.filter((s) => s.id === 'left' || s.id === 'right')
    // Las dos ramas no se pisan.
    expect(Math.abs(left.y - right.y)).toBeGreaterThanOrEqual(NODE_HEIGHT)
    expect(layout.edges.map((e) => e.id)).toEqual([
      'plan->left',
      'plan->right',
      'left->merge',
      'right->merge',
    ])
  })

  it('mantiene el orden del YAML y marca las dependencias opcionales', () => {
    const layout = layoutStages([stage('b'), stage('a', ['b?'])])
    expect(layout.stages.map((s) => s.id)).toEqual(['b', 'a'])
    expect(layout.edges).toEqual([{ id: 'b->a', from: 'b', to: 'a', optional: true }])
  })

  it('no dibuja dependencias a fases que no existen ni a sí misma', () => {
    const layout = layoutStages([stage('a', ['ghost', 'a'])])
    expect(layout.edges).toEqual([])
    expect(layout.stages).toHaveLength(1)
  })

  it('una parallel crece con sus hijos, apilados dentro de ella', () => {
    const layout = layoutStages([stage('review', [], ['security', 'style', 'tests'])])
    const [box] = layout.stages
    expect(box.height).toBeGreaterThan(NODE_HEIGHT)
    expect(box.children.map((c) => c.id)).toEqual(['security', 'style', 'tests'])
    expect(box.children[0].y).toBe(GROUP_HEADER)
    for (const child of box.children) {
      expect(child.x).toBeGreaterThan(0)
      expect(child.x + child.width).toBeLessThan(box.width)
      expect(child.y + child.height).toBeLessThan(box.height)
    }
    expect(box.children[1].y).toBeGreaterThan(box.children[0].y + box.children[0].height)
  })
})

describe('carriles por macrofase', () => {
  it('sin `phase` en ninguna fase no hay carriles', () => {
    const stages = [stage('a'), stage('b', ['a'])]
    expect(lanesOf(stages).size).toBe(0)
    expect(layoutStages(stages).lanes).toEqual([])
  })

  it('una fase sin `phase` toma el carril más avanzado de las que espera', () => {
    const lanes = lanesOf([
      phased('spec', 'spec'),
      phased('code', 'implement', ['spec']),
      phased('notes', null, ['spec']),
      phased('after', null, ['notes', 'code']),
      phased('first', null),
    ])
    expect(Object.fromEntries(lanes)).toEqual({
      spec: 'spec',
      code: 'implement',
      notes: 'spec',
      after: 'implement',
      first: 'spec',
    })
  })

  it('los carriles van en el orden de las macrofases, sin pisarse, y cada fase dentro del suyo', () => {
    const layout = layoutStages([
      phased('review', 'review', ['code']),
      phased('code', 'implement', ['plan']),
      phased('plan', 'plan'),
      phased('tests', 'implement', ['plan']),
    ])
    expect(layout.lanes.map((l) => [l.id, l.label])).toEqual([
      ['plan', 'Planificación'],
      ['implement', 'Implementación'],
      ['review', 'Revisión'],
    ])
    for (const [left, right] of layout.lanes.slice(0, -1).map((l, i) => [l, layout.lanes[i + 1]]))
      expect(right.x).toBeGreaterThan(left.x + left.width)
    const lane = Object.fromEntries(layout.lanes.map((l) => [l.id, l]))
    const of = { review: 'review', code: 'implement', plan: 'plan', tests: 'implement' } as const
    for (const box of layout.stages) {
      const band = lane[of[box.id as keyof typeof of]]
      expect(box.x).toBeGreaterThanOrEqual(band.x)
      expect(box.x + box.width).toBeLessThanOrEqual(band.x + band.width)
      expect(box.y).toBeGreaterThanOrEqual(band.y + LANE_HEADER)
      expect(box.y + box.height).toBeLessThanOrEqual(band.y + band.height)
    }
    expect(layout.width).toBeGreaterThan(lane.review.x + lane.review.width)
  })
})

const definition = (over: Partial<StageDefinition>): StageDefinition => ({
  id: 's',
  name: null,
  type: 'agent',
  agent: null,
  prompt: null,
  dependsOn: [],
  workspace: 'INHERIT',
  workspaceFrom: null,
  command: null,
  children: [],
  condition: null,
  outputSchema: null,
  outputSchemaPath: null,
  checks: [],
  approval: null,
  retry: null,
  timeoutSeconds: null,
  phase: null,
  memory: null,
  ...over,
})

describe('nodos de la definición', () => {
  it('cada tipo dice qué hace en una línea', () => {
    expect(stageDetail(definition({ agent: 'reviewer' }))).toBe('reviewer')
    expect(stageDetail(definition({}))).toBe('Agente del repositorio')
    expect(stageDetail(definition({ type: 'command', command: 'npm test' }))).toBe('npm test')
    expect(
      stageDetail(
        definition({
          type: 'parallel',
          children: [
            { id: 'a', agent: 'a', prompt: null, retry: null },
            { id: 'b', agent: 'b', prompt: null, retry: null },
          ],
        }),
      ),
    ).toBe('2 a la vez')
    expect(stageDetail(definition({ type: 'conditional', condition: 'inputs.deploy' }))).toBe(
      'Si inputs.deploy',
    )
    expect(
      stageDetail(
        definition({ type: 'verification', checks: [{ name: 'diff', paths: [], command: null }] }),
      ),
    ).toBe('1 comprobación')
    expect(
      stageDetail(
        definition({
          type: 'human-approval',
          approval: { action: 'Fusionar', risk: 'HIGH', revise: null },
        }),
      ),
    ).toBe('Aprobar: Fusionar')
  })

  it('avisa de reintentos, límite de tiempo y condición', () => {
    expect(
      stageHints(
        definition({
          retry: { maxAttempts: 3, on: ['error'], delaySeconds: 0 },
          timeoutSeconds: 1800,
          condition: 'inputs.x',
        }),
      ),
    ).toEqual(['hasta 3 intentos', 'máx. 30 min', 'si inputs.x'])
    expect(stageHints(definition({ type: 'conditional', condition: 'inputs.x' }))).toEqual([])
  })

  it('solo una parallel tiene hijos en el grafo', () => {
    const child = { id: 'a', agent: 'a', prompt: null, retry: null }
    expect(
      toGraphStages({
        key: 'k',
        name: null,
        description: null,
        inputs: [],
        agents: [],
        stages: [
          definition({ id: 'p', type: 'parallel', children: [child] }),
          definition({ id: 'q', children: [child] }),
        ],
      }).map((s) => s.children),
    ).toEqual([['a'], []])
  })
})
