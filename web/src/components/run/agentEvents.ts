import type { StoredEvent } from '../../api'

/** Eventos de un agente, en orden de secuencia. */
export function eventsOfAgent(events: StoredEvent[], agentId: string): StoredEvent[] {
  return events.filter((e) => e.aggregateType === 'agent_run' && e.aggregateId === agentId)
}

export interface AgentMessage {
  sequence: number
  occurredAt: string
  text: string
  /** Si el mensaje es de un subagente, la herramienta que lo lanzó. */
  parentToolUseId: string | null
}

/** Bloques de texto del asistente. */
export function messagesOf(events: StoredEvent[]): AgentMessage[] {
  return events
    .filter((e) => e.type === 'agent.message.received')
    .map((e) => ({
      sequence: e.sequence,
      occurredAt: e.occurredAt,
      text: stringOrNull(e.payload.text) ?? '',
      parentToolUseId: stringOrNull(e.payload.parentToolUseId),
    }))
}

export interface ToolCall {
  toolUseId: string
  name: string
  input: unknown
  startedSequence: number
  startedAt: string
  /** Null mientras la herramienta sigue en curso. */
  completedSequence: number | null
  completedAt: string | null
  isError: boolean
  output: string | null
  outputTruncated: boolean
  /** Si la lanzó un subagente, la herramienta (Task) que lo lanzó. */
  parentToolUseId: string | null
}

/** Llamadas a herramientas: cada inicio emparejado con su resultado por `toolUseId`. */
export function toolCallsOf(events: StoredEvent[]): ToolCall[] {
  const calls = new Map<string, ToolCall>()
  for (const e of events) {
    const id = stringOrNull(e.payload.toolUseId)
    if (id == null) continue
    if (e.type === 'agent.tool.started') {
      calls.set(id, {
        toolUseId: id,
        name: stringOrNull(e.payload.name) ?? '?',
        input: e.payload.input ?? null,
        startedSequence: e.sequence,
        startedAt: e.occurredAt,
        completedSequence: null,
        completedAt: null,
        isError: false,
        output: null,
        outputTruncated: false,
        parentToolUseId: stringOrNull(e.payload.parentToolUseId),
      })
    } else if (e.type === 'agent.tool.completed') {
      const call = calls.get(id)
      if (call == null) continue
      call.completedSequence = e.sequence
      call.completedAt = e.occurredAt
      call.isError = e.payload.isError === true
      call.output = stringOrNull(e.payload.output)
      call.outputTruncated = e.payload.outputTruncated === true
    }
  }
  return [...calls.values()]
}

/** Rama del worktree y texto final del agente, sacados de sus eventos. */
export function agentOutcome(events: StoredEvent[]): {
  branch: string | null
  result: string | null
} {
  const workspace = events.findLast((e) => e.type === 'agent.workspace.ready')
  const result = events.findLast((e) => e.type === 'agent.result')
  return {
    branch: stringOrNull(workspace?.payload.branch),
    result: stringOrNull(result?.payload.result),
  }
}

const stringOrNull = (v: unknown) => (typeof v === 'string' ? v : null)
