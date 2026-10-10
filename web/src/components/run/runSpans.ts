import type { Run } from '../../api'
import { isTerminal, statusTone, type StatusTone } from '../../format'
import type { ToolCall } from './agentEvents'

/**
 * Tramo de tiempo de la ejecución: una fase, un agente o una llamada a una herramienta. El árbol y
 * la cascada pintan los mismos tramos, uno como lista y otro sobre un eje de tiempo.
 */
export interface Span {
  id: string
  kind: 'stage' | 'agent' | 'tool'
  label: string
  /** Texto secundario: el argumento principal de una herramienta, el tipo de un agente… */
  detail: string
  /** Estado tal como lo devuelve el API (`RUNNING`, `FAILED`…); en las herramientas, derivado. */
  status: string
  tone: StatusTone
  /** Milisegundos desde epoch; null si aún no ha empezado. */
  start: number | null
  /** Null mientras sigue en curso. */
  end: number | null
  agentId: string | null
  toolUseId: string | null
  children: Span[]
}

const ms = (iso: string | null) => (iso ? new Date(iso).getTime() : null)

/** Estado de una llamada: sin resultado y con el agente ya terminado, se quedó sin respuesta. */
export function toolStatus(call: ToolCall, agentDone: boolean): string {
  if (call.completedSequence != null) return call.isError ? 'ERROR' : 'COMPLETED'
  return agentDone ? 'INTERRUPTED' : 'EXECUTING'
}

/** Fases → agentes → herramientas (y las de un subagente, bajo la herramienta que lo lanzó). */
export function runSpans(run: Run, toolsOf: (agentId: string) => ToolCall[]): Span[] {
  return run.stages.map((stage) => ({
    id: stage.id,
    kind: 'stage',
    label: stage.name ?? stage.stageKey,
    detail: stage.attempt > 1 ? `intento ${stage.attempt}` : '',
    status: stage.status,
    tone: statusTone(stage.status),
    start: ms(stage.startedAt),
    end: ms(stage.finishedAt),
    agentId: null,
    toolUseId: null,
    children: stage.agents.map((agent) => {
      const done = isTerminal(agent.status)
      const end = ms(agent.finishedAt) ?? (done ? ms(agent.lastActivityAt) : null)
      const calls = toolsOf(agent.id)
      const tools = calls.map((call): Span => {
        const status = toolStatus(call, done)
        return {
          id: call.toolUseId,
          kind: 'tool',
          label: call.name,
          detail: summary(call.input),
          status,
          tone: statusTone(status),
          start: ms(call.startedAt),
          // Una herramienta que nunca respondió acaba, como mucho, cuando acaba su agente.
          end: ms(call.completedAt) ?? (done ? end : null),
          agentId: agent.id,
          toolUseId: call.toolUseId,
          children: [],
        }
      })
      return {
        id: agent.id,
        kind: 'agent',
        label: agent.provider,
        detail: agent.kind,
        status: agent.status,
        tone: statusTone(agent.status),
        start: ms(agent.startedAt) ?? ms(agent.createdAt),
        end,
        agentId: agent.id,
        toolUseId: null,
        children: nest(tools, calls),
      } satisfies Span
    }),
  }))
}

/** Las llamadas de un subagente cuelgan de la llamada que lo lanzó. */
function nest(spans: Span[], calls: ToolCall[]): Span[] {
  const byId = new Map(spans.map((s) => [s.id, s]))
  const roots: Span[] = []
  calls.forEach((call, i) => {
    const parent = call.parentToolUseId ? byId.get(call.parentToolUseId) : undefined
    if (parent) parent.children.push(spans[i])
    else roots.push(spans[i])
  })
  return roots
}

const TOOL_STATUS_TEXT: Record<string, string> = {
  EXECUTING: 'en curso',
  COMPLETED: 'hecha',
  ERROR: 'con error',
  INTERRUPTED: 'sin respuesta',
}

/** Estado de una llamada en palabras, para lectores de pantalla. */
export const toolStatusText = (span: Span) => TOOL_STATUS_TEXT[span.status] ?? span.status

/** Duración de un tramo; si sigue en curso, hasta `now`. */
export const spanDuration = (span: Span, now: number) =>
  span.start == null ? null : (span.end ?? now) - span.start

/** Recorrido en orden: cada tramo seguido de sus hijos, con su profundidad. */
export function flatten(spans: Span[], depth = 0): { span: Span; depth: number }[] {
  return spans.flatMap((span) => [{ span, depth }, ...flatten(span.children, depth + 1)])
}

function summary(input: unknown): string {
  if (input == null || typeof input !== 'object') return ''
  const i = input as Record<string, unknown>
  const value = i.command ?? i.file_path ?? i.pattern ?? i.path ?? i.url ?? i.description
  return typeof value === 'string' ? value : ''
}
