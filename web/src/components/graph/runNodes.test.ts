import { describe, expect, it } from 'vitest'
import type { AgentRun, StageRun } from '../../api'
import { stageFacts, toRunGraphStages } from './runNodes'

const agent = (over: Partial<AgentRun>) =>
  ({ provider: 'claude-code', costUsd: null, childKey: null, ...over }) as AgentRun

const stage = (over: Partial<StageRun>): StageRun =>
  ({
    id: 's',
    stageKey: 's',
    type: 'agent',
    agent: null,
    dependsOn: [],
    children: [],
    agents: [],
    attempt: 1,
    retry: null,
    retriedBy: null,
    ...over,
  }) as StageRun

describe('nodos de la ejecución', () => {
  it('una parallel lleva los hijos del YAML, o los de sus agentes si no se leen', () => {
    expect(
      toRunGraphStages([
        stage({ stageKey: 'review', type: 'parallel', children: ['calidad', 'seguridad'] }),
        stage({
          stageKey: 'old',
          type: 'parallel',
          agents: [agent({ childKey: 'a' }), agent({ childKey: 'b' }), agent({ childKey: 'a' })],
        }),
        stage({ stageKey: 'fix', dependsOn: [{ stage: 'review', optional: true }] }),
      ]),
    ).toEqual([
      { id: 'review', dependsOn: [], children: ['calidad', 'seguridad'] },
      { id: 'old', dependsOn: [], children: ['a', 'b'] },
      { id: 'fix', dependsOn: [{ stage: 'review', optional: true }], children: [] },
    ])
  })

  it('dice quién la hace, lo que cuesta y en qué intento va', () => {
    expect(stageFacts(stage({ agent: 'developer' }))).toEqual(['developer'])
    expect(
      stageFacts(
        stage({
          agents: [agent({ provider: 'codex', costUsd: 0.25 }), agent({ costUsd: 0.5 })],
          attempt: 2,
          retry: { maxAttempts: 3, on: ['error'], delaySeconds: 0 },
        }),
      ),
    ).toEqual(['Claude Code', '0,75 US$', 'intento 2 de 3'])
    // Un comando o una parallel no tienen un agente que nombrar.
    expect(
      stageFacts(stage({ type: 'command', agents: [agent({ provider: 'command' })] })),
    ).toEqual([])
  })
})
