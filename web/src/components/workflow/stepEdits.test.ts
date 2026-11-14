import { describe, expect, it } from 'vitest'
import { addStep, EditError, moveStage, readStage, readStages } from './definitionEdits'
import { applyQuickFix, quickFixOf } from './quickFix'

const CHAIN = `id: demo
agents:
  dev:
    prompt: Hazlo
  revisor:
    prompt: Revisa
stages:
  - id: spec
    type: agent
    agent: dev
    phase: spec
  - id: plan
    type: agent
    agent: dev
    dependsOn: [spec]
    phase: plan
  - id: implementar
    type: agent
    agent: dev
    dependsOn: [plan]
    phase: implement
`

const deps = (text: string) =>
  Object.fromEntries(readStages(text).map((s) => [s.id, s.dependsOn.map((d) => d.stage)]))
const order = (text: string) => readStages(text).map((s) => s.id)

describe('añadir un paso (WF-F)', () => {
  it('entre dos pasos: depende del de arriba, el de abajo pasa a depender de él y va en su etapa', () => {
    const { text, id } = addStep(CHAIN, 'agent', 'spec')
    expect(id).toBe('agente')
    expect(order(text)).toEqual(['spec', 'agente', 'plan', 'implementar'])
    expect(deps(text)).toMatchObject({ agente: ['spec'], plan: ['agente'] })
    expect(readStage(text, 'agente')).toMatchObject({ type: 'agent', prompt: '', phase: 'spec' })
  })

  it('un juez lleva el primer agente del workflow', () => {
    const { text, id } = addStep(CHAIN, 'judge', 'implementar')
    expect(readStage(text, id)).toMatchObject({
      type: 'verification',
      judge: { agents: ['dev'] },
      dependsOn: ['implementar'],
    })
  })

  it('la entrega por PR son cuatro pasos en cadena y la CI la corrige el último agente', () => {
    const { text, id } = addStep(CHAIN, 'delivery', 'implementar')
    expect(id).toBe('pr')
    expect(order(text)).toEqual([
      'spec',
      'plan',
      'implementar',
      'pr',
      'ci',
      'aprobar-fusion',
      'fusionar',
    ])
    expect(deps(text)).toMatchObject({
      pr: ['implementar'],
      ci: ['pr'],
      'aprobar-fusion': ['ci'],
      fusionar: ['aprobar-fusion'],
    })
    expect(readStage(text, 'ci')).toMatchObject({ type: 'github-check', fix: 'implementar' })
    expect(readStage(text, 'fusionar')).toMatchObject({
      type: 'github-merge',
      phase: 'deliver',
      condition: 'repository.github',
    })
  })

  it('no rompe un `workspaceFrom`: si el de abajo continúa el worktree, no se reengancha', () => {
    const text = CHAIN.replace(
      '    dependsOn: [spec]\n',
      '    dependsOn: [spec]\n    workspaceFrom: spec\n',
    )
    const result = addStep(text, 'command', 'spec')
    expect(deps(result.text)).toMatchObject({ comando: ['spec'], plan: ['spec'] })
  })
})

describe('mover un paso (WF-F)', () => {
  it('en una línea, subir uno intercambia también las dependencias', () => {
    const text = moveStage(CHAIN, 'plan', 'up')
    expect(order(text)).toEqual(['plan', 'spec', 'implementar'])
    expect(deps(text)).toEqual({ plan: [], spec: ['plan'], implementar: ['spec'] })
    // Bajarlo lo deja como estaba (salvo dónde va `dependsOn` dentro de la fase).
    const back = moveStage(text, 'plan', 'down')
    expect(order(back)).toEqual(order(CHAIN))
    expect(deps(back)).toEqual(deps(CHAIN))
  })

  it('dos pasos que no dependen uno del otro solo cambian de orden', () => {
    const text = CHAIN.replace('    dependsOn: [plan]\n', '    dependsOn: [spec]\n')
    const moved = moveStage(text, 'implementar', 'up')
    expect(order(moved)).toEqual(['spec', 'implementar', 'plan'])
    expect(deps(moved)).toEqual(deps(text))
  })

  it('dice por qué no cuando no es solo cambiar el orden', () => {
    expect(() => moveStage(CHAIN, 'spec', 'up')).toThrow('Ya es el primer paso')
    expect(() => moveStage(CHAIN, 'implementar', 'down')).toThrow('Ya es el último paso')
    const branched = CHAIN.replace('    dependsOn: [plan]\n', '    dependsOn: [plan, spec]\n')
    expect(() => moveStage(branched, 'plan', 'up')).toThrow(/otras fases también dependen/)
    const reads = CHAIN.replace(
      '    dependsOn: [spec]\n',
      '    dependsOn: [spec]\n    prompt: "Usa {{stages.spec.output.summary}}"\n',
    )
    expect(() => moveStage(reads, 'plan', 'up')).toThrow(EditError)
    expect(() => moveStage(reads, 'plan', 'up')).toThrow(/usa lo que devuelve/)
  })
})

describe('arreglos en un clic (WF-F)', () => {
  const problem = {
    severity: 'ERROR' as const,
    path: 'stages.implementar.dependsOn[0]',
    line: 20,
    column: 16,
    message: 'No hay ninguna fase `plann`; ¿quisiste decir `plan`?',
  }

  it('cambia en su línea el nombre que está mal por el sugerido', () => {
    const text = CHAIN.replace('dependsOn: [plan]', 'dependsOn: [plann]')
    expect(quickFixOf(problem)).toEqual({ from: 'plann', to: 'plan' })
    expect(applyQuickFix(text, problem)).toBe(CHAIN)
  })

  it('sin sugerencia, o si el texto ya cambió, no propone nada', () => {
    expect(quickFixOf({ ...problem, message: 'No hay ninguna fase `x`' })).toBeNull()
    expect(applyQuickFix(CHAIN, problem)).toBeNull()
  })
})
