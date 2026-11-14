import { describe, expect, it } from 'vitest'
import type { AgentDefinition, StageDefinition, WorkflowModel } from '../../api'
import { figuresOf, permissionText, readinessOf, receivesFrom, stepsOf } from './steps'

const stage = (id: string, extra: Partial<StageDefinition> = {}): StageDefinition => ({
  id,
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
  pullRequest: null,
  ci: null,
  merge: null,
  judge: null,
  ...extra,
})

const agent = (name: string, extra: Partial<AgentDefinition> = {}): AgentDefinition => ({
  name,
  description: null,
  prompt: null,
  tools: null,
  permissionMode: null,
  maxTurns: null,
  maxBudgetUsd: null,
  timeoutMinutes: null,
  model: null,
  provider: null,
  output: null,
  onQuota: null,
  fallback: null,
  ...extra,
})

const definition: WorkflowModel = {
  key: 'demo',
  name: 'Demo',
  description: null,
  inputs: [],
  risk: { paths: [], patterns: [] },
  agents: [
    agent('analista', {
      provider: 'antigravity',
      description: 'Escribe la propuesta.',
      permissionMode: 'acceptEdits',
      fallback: { provider: 'claude-code', model: null },
    }),
    agent('revisor', { provider: 'codex', permissionMode: 'plan' }),
  ],
  stages: [
    stage('spec', {
      name: 'Especifica la propuesta',
      agent: 'analista',
      phase: 'spec',
      checks: [{ name: 'artifacts', command: null, paths: ['openspec/proposal.md'] }],
      retry: { maxAttempts: 2, on: ['error'], delaySeconds: 0 },
    }),
    stage('aprobar', {
      name: 'Apruebas el plan',
      type: 'human-approval',
      phase: 'plan',
      dependsOn: [{ stage: 'spec', optional: false }],
      approval: { action: 'Aprobar', risk: 'MEDIUM', revise: 'spec' },
    }),
    stage('implementar', {
      name: 'Implementa',
      phase: 'implement',
      prompt: 'Hazlo con {{stages.spec.output.summary}} y {{ stages.spec.output.risks }}.',
      dependsOn: [{ stage: 'aprobar', optional: false }],
      checks: [{ name: 'tests', command: null, paths: [] }],
    }),
    stage('juzgar', {
      name: 'Juzga',
      type: 'verification',
      phase: 'review',
      judge: {
        agents: [{ agent: 'revisor', lens: null }],
        rubric: [
          { id: 'a', text: 'A', weight: 1, required: true },
          { id: 'b', text: 'B', weight: 1, required: false },
        ],
        rubricFile: null,
        threshold: 0.7,
        fix: 'implementar',
        maxRounds: 2,
        refute: 2,
        refuteAgent: null,
        guide: null,
      },
    }),
    stage('pr', { name: 'Abre la PR', type: 'github-pr', phase: 'deliver' }),
  ],
}

describe('paso a paso', () => {
  const groups = stepsOf(definition)
  const steps = groups.flatMap((g) => g.steps)

  it('agrupa por etapa y numera en el orden del YAML', () => {
    expect(groups.map((g) => g.lane)).toEqual(['spec', 'plan', 'implement', 'review', 'deliver'])
    expect(steps.map((s) => `${s.n}. ${s.title} (${s.kind})`)).toEqual([
      '1. Especifica la propuesta (Agente)',
      '2. Apruebas el plan (Tu aprobación)',
      '3. Implementa (Agente)',
      '4. Juzga (Juez)',
      '5. Abre la PR (GitHub)',
    ])
  })

  it('cuenta quién lo hace y qué hace, con frases de la definición', () => {
    expect(steps[0].workers).toEqual([
      { agent: 'analista', provider: 'antigravity', model: null, fallback: 'claude-code' },
    ])
    expect(steps[0].phrase).toBe('Escribe la propuesta.')
    expect(steps[1].phrase).toBe(
      'Se para hasta que alguien decide en Aprobaciones. Si pides cambios, los hace «Especifica la propuesta».',
    )
    expect(steps[3].phrase).toBe(
      'Puntúa 2 criterios (1 obligatorio). 2 escépticos intentan refutar cada hallazgo que bloquea. Lo que queda lo corrige «Implementa», como mucho 2 rondas.',
    )
    expect(steps[2].workers[0].provider).toBe('repository')
  })

  it('pone en chips lo que deja, recibe, comprueba, reintenta y el riesgo', () => {
    const texts = (i: number) => steps[i].chips.map((c) => c.text)
    expect(texts(0)).toEqual(['Deja openspec/proposal.md', 'Si falla, lo intenta 2 veces'])
    expect(texts(1)).toContain('Riesgo medio')
    expect(steps[1].chips.find((c) => c.icon === 'risk')?.tone).toBe('warn')
    expect(texts(2)).toEqual([
      'Después de «Apruebas el plan»',
      'Recibe de «Especifica la propuesta»: summary, risks',
      'Tests en verde',
    ])
  })

  it('saca las salidas que lee un prompt', () => {
    expect(
      receivesFrom('{{stages.a.output}} y {{stages.b.output.x}} {{stages.b.output.y}}'),
    ).toEqual([
      { stage: 'a', fields: [] },
      { stage: 'b', fields: ['x', 'y'] },
    ])
  })

  it('resume las cifras, los permisos y lo que necesita para lanzar', () => {
    expect(figuresOf(definition)).toMatchObject({
      stages: 5,
      lanes: 5,
      agents: 2,
      gates: 1,
      judges: 1,
      delivers: true,
    })
    expect(permissionText(definition.agents[0])).toBe('Puede editar ficheros')
    expect(permissionText(definition.agents[1])).toBe('Solo lectura')
    expect(readinessOf(definition, []).map((i) => i.text)).toEqual([
      'La definición no tiene errores.',
      'El repositorio necesita su comando de verificación: lo usan los tests de «Implementa».',
      // La fase sin agente usa el del repositorio: su proveedor no se sabe aquí.
      'Necesita cuota de Antigravity CLI y Codex.',
      'La fase de GitHub solo corre si el repositorio está en GitHub con la App.',
    ])
    expect(
      readinessOf(null, [{ severity: 'ERROR', path: 'x', line: 1, column: 1, message: 'mal' }])[0],
    ).toEqual({ tone: 'bad', text: 'Tiene 1 error: corrígelos para lanzarlo.' })
  })
})
