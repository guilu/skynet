import { describe, expect, it } from 'vitest'
import runPage from '../../../../fixtures/contracts/run-page.json'
import type { Run, StoredEvent } from '../../api'
import { applyEvent } from './applyEvent'

const run = runPage.items[0] as Run
const agent = run.stages[0].agents[0]

function ev(type: string, payload: Record<string, unknown>, aggregateId = agent.id): StoredEvent {
  return {
    sequence: 1,
    eventId: 'e',
    workflowRunId: run.id,
    aggregateType: type.startsWith('stage') ? 'stage_run' : 'agent_run',
    aggregateId,
    type,
    payload,
    occurredAt: '2026-10-05T10:00:00Z',
    recordedAt: '',
  }
}

const agentOf = (r: Run) => r.stages[0].agents[0]

describe('applyEvent', () => {
  it('fija la herramienta en curso y la quita al terminar, sin releer', () => {
    const started = applyEvent(run, ev('agent.tool.started', { name: 'Bash' }))
    expect(agentOf(started.run).currentTool).toBe('Bash')
    expect(agentOf(started.run).lastEventType).toBe('agent.tool.started')
    expect(agentOf(started.run).lastActivityAt).toBe('2026-10-05T10:00:00Z')
    expect(started.refetch).toBe('none')

    const done = applyEvent(started.run, ev('agent.tool.completed', { name: 'Bash' }))
    expect(agentOf(done.run).currentTool).toBeNull()
  })

  it('pide releer sin prisa cuando el evento trae tokens', () => {
    expect(applyEvent(run, ev('agent.message.received', { usage: { input: 1 } })).refetch).toBe(
      'soon',
    )
  })

  it('cambia el estado del agente y relee al terminar', () => {
    const thinking = applyEvent(run, ev('agent.status.changed', { status: 'THINKING' }))
    expect(agentOf(thinking.run).status).toBe('THINKING')
    expect(agentOf(thinking.run).currentTool).toBe(agent.currentTool)
    expect(thinking.refetch).toBe('none')

    expect(applyEvent(run, ev('agent.status.changed', { status: 'STARTING' })).refetch).toBe('now')

    const failed = applyEvent(run, ev('agent.status.changed', { status: 'FAILED' }))
    expect(agentOf(failed.run).status).toBe('FAILED')
    expect(agentOf(failed.run).currentTool).toBeNull()
    expect(failed.refetch).toBe('now')
  })

  it('cambia el estado de la fase', () => {
    const r = applyEvent(run, ev('stage.status.changed', { status: 'RUNNING' }, run.stages[0].id))
    expect(r.run.stages[0].status).toBe('RUNNING')
  })

  it('relee cuando aparece algo que la vista no tiene', () => {
    expect(applyEvent(run, ev('agent.spawned', {})).refetch).toBe('now')
    expect(applyEvent(run, ev('agent.tool.started', { name: 'X' }, 'otro')).refetch).toBe('now')
    expect(applyEvent(run, ev('agent.result', {})).refetch).toBe('now')
  })

  it('no retrocede la última actividad al reaplicar el histórico', () => {
    const later = applyEvent(run, ev('agent.tool.started', { name: 'A' })).run
    const old = { ...ev('agent.message.received', {}), occurredAt: '2026-10-05T08:00:00Z' }
    expect(agentOf(applyEvent(later, old).run).lastActivityAt).toBe('2026-10-05T10:00:00Z')
  })
})
