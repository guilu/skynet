import { describe, expect, it } from 'vitest'
import type { StageDefinition } from '../../api'
import { stageDetail, stageHints, toGraphStages } from './definitionNodes'
import { GROUP_HEADER, layoutStages, NODE_HEIGHT, type GraphStage } from './layout'

const stage = (id: string, dependsOn: string[] = [], children: string[] = []): GraphStage => ({
  id,
  dependsOn: dependsOn.map((d) => ({ stage: d.replace('?', ''), optional: d.endsWith('?') })),
  children,
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
