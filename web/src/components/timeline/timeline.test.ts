import { describe, expect, it } from 'vitest'
import type { StoredEvent } from '../../api'
import {
  aggregateTimeline,
  indexOfSequence,
  matchesFilters,
  originOf,
  severityOf,
} from './timeline'

let seq = 0
function ev(
  type: string,
  payload: Record<string, unknown> = {},
  aggregate: { type?: string; id?: string } = {},
): StoredEvent {
  seq += 1
  return {
    sequence: seq,
    eventId: `e${seq}`,
    workflowRunId: 'r1',
    aggregateType: aggregate.type ?? 'agent_run',
    aggregateId: aggregate.id ?? 'a1',
    type,
    payload,
    occurredAt: '2026-10-05T09:00:00Z',
    recordedAt: '',
  }
}

const status = (previousStatus: string, s: string) =>
  ev('agent.status.changed', { previousStatus, status: s })

/** Una llamada completa: inicio, paso a ejecutar, resultado y vuelta a pensar. */
function call(id: string, name: string, input: unknown, isError = false): StoredEvent[] {
  return [
    ev('agent.tool.started', { toolUseId: id, name, input }),
    status('THINKING', 'EXECUTING'),
    ev('agent.tool.completed', { toolUseId: id, name, isError }),
    status('EXECUTING', 'THINKING'),
  ]
}

describe('aggregateTimeline', () => {
  it('une inicio y fin de una herramienta y pliega sus cambios de estado', () => {
    const events = call('t1', 'Bash', { command: 'ls' })
    const [entry, ...rest] = aggregateTimeline(events)
    expect(rest).toEqual([])
    expect(entry.title).toBe('Bash: ls')
    expect(entry.kind).toBe('tool')
    expect(entry.events.map((e) => e.sequence)).toEqual(events.map((e) => e.sequence))
  })

  it('marca la herramienta en curso y la que falla', () => {
    const running = ev('agent.tool.started', { toolUseId: 'x', name: 'Bash', input: {} })
    expect(aggregateTimeline([running])[0].title).toBe('Bash (en curso)')
    const [failed] = aggregateTimeline(call('y', 'Bash', { command: 'false' }, true))
    expect(failed.title).toBe('Bash: false (falló)')
    expect(failed.severity).toBe('error')
  })

  it('agrupa las llamadas seguidas a la misma herramienta y conserva cada una', () => {
    const events = [
      ...call('r1', 'Read', { file_path: '/a' }),
      ...call('r2', 'Read', { file_path: '/b' }),
      ...call('r3', 'Read', { file_path: '/c' }),
      ...call('e1', 'Edit', { file_path: '/a' }),
    ]
    const [group, edit] = aggregateTimeline(events)
    expect(group.title).toBe('Leídos 3 ficheros')
    expect(group.children.map((c) => c.title)).toEqual(['Read: /a', 'Read: /b', 'Read: /c'])
    expect(group.events).toHaveLength(12)
    expect(edit.title).toBe('Edit: /a')
    expect(edit.children).toEqual([])
  })

  it('no agrupa a través de otros eventos ni de otro agente', () => {
    const entries = aggregateTimeline([
      ...call('r1', 'Read', {}),
      ev('agent.message.received', { text: 'hola' }),
      ...call('r2', 'Read', {}),
      ev('agent.tool.started', { toolUseId: 'r3', name: 'Read', input: {} }, { id: 'a2' }),
    ])
    expect(entries.map((e) => e.children.length)).toEqual([0, 0, 0, 0])
  })

  it('deja como entradas propias los cambios de estado que no vienen de una herramienta', () => {
    const entries = aggregateTimeline([
      status('STARTING', 'THINKING'),
      ev('agent.message.received', { text: 'hola' }),
      status('THINKING', 'EXECUTING'),
    ])
    expect(entries.map((e) => e.kind)).toEqual(['status', 'message', 'status'])
  })

  it('asigna la fase del agente y la de los eventos de fase', () => {
    const entries = aggregateTimeline(
      [
        ev('stage.ready', { stageKey: 'agent', attempt: 1 }, { type: 'stage_run', id: 's1' }),
        ev('agent.message.received', { text: 'x' }),
        ev('workflow.started', {}, { type: 'workflow_run', id: 'r1' }),
      ],
      { stageOfAgent: (id) => (id === 'a1' ? 's1' : undefined) },
    )
    expect(entries.map((e) => e.stageId)).toEqual(['s1', 's1', null])
  })
})

describe('clasificación', () => {
  it('distingue el origen', () => {
    expect(originOf(ev('agent.message.received'))).toBe('agent')
    expect(originOf(ev('agent.process.exited'))).toBe('runner')
    expect(originOf(status('THINKING', 'FAILED'))).toBe('control-plane')
    expect(originOf(ev('workflow.started', {}, { type: 'workflow_run' }))).toBe('control-plane')
  })

  it('distingue la severidad', () => {
    expect(severityOf(status('THINKING', 'FAILED'))).toBe('error')
    expect(severityOf(status('THINKING', 'UNRESPONSIVE'))).toBe('warn')
    expect(severityOf(ev('agent.process.exited', { exitCode: 0 }))).toBe('info')
    expect(severityOf(ev('agent.process.exited', { exitCode: 143, signal: 'SIGTERM' }))).toBe(
      'warn',
    )
    expect(severityOf(ev('agent.process.exited', { exitCode: 1 }))).toBe('error')
    expect(severityOf(ev('agent.rate_limit', { status: 'rejected' }))).toBe('warn')
  })
})

describe('filtros', () => {
  const entries = aggregateTimeline([
    ev('agent.message.received', { text: 'x' }),
    ...call('b', 'Bash', {}, true),
    ev('agent.message.received', { text: 'y' }, { id: 'a2' }),
  ])

  it('filtran por tipo, severidad mínima, agente y origen', () => {
    expect(entries.filter((e) => matchesFilters(e, { kind: 'message' }))).toHaveLength(2)
    expect(entries.filter((e) => matchesFilters(e, { severity: 'warn' }))).toHaveLength(1)
    expect(entries.filter((e) => matchesFilters(e, { agent: 'a2' }))).toHaveLength(1)
    expect(entries.filter((e) => matchesFilters(e, { origin: 'runner' }))).toHaveLength(0)
    expect(entries.filter((e) => matchesFilters(e, {}))).toHaveLength(3)
  })

  it('encuentran la entrada que contiene un evento', () => {
    const toolCompleted = entries[1].events[2]
    expect(indexOfSequence(entries, toolCompleted.sequence)).toBe(1)
    expect(indexOfSequence(entries, 99999)).toBe(-1)
  })
})
