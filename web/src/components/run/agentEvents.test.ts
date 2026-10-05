import { describe, expect, it } from 'vitest'
import type { StoredEvent } from '../../api'
import { agentOutcome, eventsOfAgent, messagesOf, toolCallsOf } from './agentEvents'

let seq = 0
function event(type: string, payload: Record<string, unknown>, agent = 'a1'): StoredEvent {
  seq += 1
  return {
    sequence: seq,
    eventId: `e${seq}`,
    workflowRunId: 'r1',
    aggregateType: 'agent_run',
    aggregateId: agent,
    type,
    payload,
    occurredAt: `2026-10-05T09:00:${String(seq).padStart(2, '0')}Z`,
    recordedAt: '',
  }
}

describe('agentEvents', () => {
  it('filtra los eventos de un agente', () => {
    const mine = event('agent.message.received', { text: 'hola' })
    const other = event('agent.message.received', { text: 'otro' }, 'a2')
    const stage = { ...event('stage.ready', {}), aggregateType: 'stage_run', aggregateId: 'a1' }
    expect(eventsOfAgent([mine, other, stage], 'a1')).toEqual([mine])
  })

  it('extrae los mensajes como texto', () => {
    const e = event('agent.message.received', { text: '<b>no es HTML</b>', parentToolUseId: null })
    expect(messagesOf([e, event('agent.tool.started', { toolUseId: 't' })])).toEqual([
      {
        sequence: e.sequence,
        occurredAt: e.occurredAt,
        text: '<b>no es HTML</b>',
        parentToolUseId: null,
      },
    ])
  })

  it('empareja cada herramienta con su resultado y deja en curso las que no lo tienen', () => {
    const read = event('agent.tool.started', {
      toolUseId: 't1',
      name: 'Read',
      input: { file_path: '/w/a' },
    })
    const bash = event('agent.tool.started', { toolUseId: 't2', name: 'Bash', input: {} })
    const done = event('agent.tool.completed', {
      toolUseId: 't1',
      name: 'Read',
      isError: true,
      output: 'no existe',
      outputTruncated: true,
    })
    const orphan = event('agent.tool.completed', { toolUseId: 'zz', name: 'X' })

    const [first, second, ...rest] = toolCallsOf([read, bash, done, orphan])
    expect(rest).toEqual([])
    expect(first).toMatchObject({
      name: 'Read',
      input: { file_path: '/w/a' },
      startedSequence: read.sequence,
      completedSequence: done.sequence,
      isError: true,
      output: 'no existe',
      outputTruncated: true,
    })
    expect(second).toMatchObject({ name: 'Bash', completedSequence: null, output: null })
  })

  it('saca la rama y el resultado final', () => {
    expect(
      agentOutcome([
        event('agent.workspace.ready', { branch: 'skynet/tkm-1' }),
        event('agent.result', { result: 'Hecho.' }),
      ]),
    ).toEqual({ branch: 'skynet/tkm-1', result: 'Hecho.' })
    expect(agentOutcome([])).toEqual({ branch: null, result: null })
  })
})
