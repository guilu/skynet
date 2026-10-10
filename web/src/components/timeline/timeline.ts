import type { StoredEvent } from '../../api'
import { describeEvent, toolSummary } from '../../format'

/** Categoría de un evento para filtrar el timeline. */
export type EventKind = 'message' | 'tool' | 'file' | 'status' | 'lifecycle' | 'result' | 'other'
export type Severity = 'info' | 'warn' | 'error'
/** Quién produce el evento: el agente (vía runner), el propio runner o el control plane. */
export type Origin = 'agent' | 'runner' | 'control-plane'

export const KIND_LABELS: Record<EventKind, string> = {
  message: 'Mensajes',
  tool: 'Herramientas',
  file: 'Ficheros y git',
  status: 'Estados',
  lifecycle: 'Sesión y proceso',
  result: 'Resultado',
  other: 'Otros',
}
export const SEVERITY_LABELS: Record<Severity, string> = {
  info: 'Todo',
  warn: 'Avisos y errores',
  error: 'Solo errores',
}
export const ORIGIN_LABELS: Record<Origin, string> = {
  agent: 'Agente',
  runner: 'Runner',
  'control-plane': 'Control plane',
}

export interface TimelineEntry {
  /** Clave estable para React y para recordar qué grupos están abiertos. */
  id: string
  /** Secuencia del primer evento: orden de la entrada en el timeline. */
  sequence: number
  occurredAt: string
  kind: EventKind
  severity: Severity
  origin: Origin
  agentId: string | null
  stageId: string | null
  title: string
  /** Eventos originales que resume la entrada, en orden. */
  events: StoredEvent[]
  /** Entradas agrupadas (p. ej. varias lecturas seguidas); vacío si no es un grupo. */
  children: TimelineEntry[]
}

export interface TimelineContext {
  /** Fase de un agente de la ejecución, para filtrar por fase. */
  stageOfAgent?: (agentId: string) => string | undefined
}

const SEVERITY_RANK: Record<Severity, number> = { info: 0, warn: 1, error: 2 }
const maxSeverity = (a: Severity, b: Severity) => (SEVERITY_RANK[a] >= SEVERITY_RANK[b] ? a : b)

export function kindOf(type: string): EventKind {
  switch (type) {
    case 'agent.message.received':
      return 'message'
    case 'agent.tool.started':
    case 'agent.tool.completed':
      return 'tool'
    case 'agent.file.changed':
    case 'agent.vcs.changed':
      return 'file'
    case 'agent.result':
      return 'result'
    case 'workflow.started':
    case 'stage.pending':
    case 'stage.ready':
    case 'stage.skipped':
    case 'stage.start.failed':
    case 'agent.spawned':
      return 'status'
    case 'agent.session.started':
    case 'agent.workspace.ready':
    case 'agent.workspace.cleanup.requested':
    case 'agent.workspace.removed':
    case 'agent.process.exited':
    case 'agent.rate_limit':
    case 'agent.permission.denied':
    case 'agent.verification.started':
    case 'agent.verification.completed':
      return 'lifecycle'
    default:
      return type.endsWith('.status.changed') ? 'status' : 'other'
  }
}

export function originOf(event: StoredEvent): Origin {
  if (
    event.type === 'agent.process.exited' ||
    event.type === 'agent.workspace.ready' ||
    event.type === 'agent.workspace.removed' ||
    event.type.startsWith('agent.verification.')
  ) {
    return 'runner'
  }
  if (event.type.startsWith('agent.') && kindOf(event.type) !== 'status') return 'agent'
  return 'control-plane'
}

export function severityOf(event: StoredEvent): Severity {
  const p = event.payload
  switch (event.type) {
    case 'agent.tool.completed':
    case 'agent.result':
      return p.isError === true ? 'error' : 'info'
    case 'agent.permission.denied':
      return 'warn'
    case 'stage.start.failed':
      return 'error'
    case 'agent.rate_limit':
      return p.status === 'allowed' ? 'info' : 'warn'
    case 'agent.verification.completed': {
      const tests = p.tests as { failed?: number; errors?: number } | undefined
      const failedTests = (tests?.failed ?? 0) + (tests?.errors ?? 0) > 0
      return p.error != null || p.exitCode !== 0 || failedTests ? 'error' : 'info'
    }
    case 'agent.process.exited':
      if (p.error != null) return 'error'
      if (p.signal != null) return 'warn'
      return p.exitCode === 0 ? 'info' : 'error'
  }
  if (event.type.endsWith('.status.changed')) {
    if (p.status === 'FAILED') return 'error'
    if (p.status === 'UNRESPONSIVE' || p.status === 'CANCELLED') return 'warn'
  }
  return 'info'
}

/** Cambio de estado entre pensar y ejecutar una herramienta: ruido si ya se ve la herramienta. */
function isToolTransition(event: StoredEvent): boolean {
  const busy = ['THINKING', 'EXECUTING']
  return (
    event.type === 'agent.status.changed' &&
    busy.includes(String(event.payload.status)) &&
    busy.includes(String(event.payload.previousStatus))
  )
}

const str = (v: unknown) => (typeof v === 'string' ? v : null)

function single(event: StoredEvent, ctx: TimelineContext): TimelineEntry {
  // Las verificaciones y los artefactos llevan su agente en el payload.
  const agentId =
    event.aggregateType === 'agent_run' ? event.aggregateId : str(event.payload.agentRunId)
  return {
    id: `e${event.sequence}`,
    sequence: event.sequence,
    occurredAt: event.occurredAt,
    kind: kindOf(event.type),
    severity: severityOf(event),
    origin: originOf(event),
    agentId,
    stageId: stageOf(event, agentId, ctx),
    title: describeEvent(event.type, event.payload),
    events: [event],
    children: [],
  }
}

function stageOf(event: StoredEvent, agentId: string | null, ctx: TimelineContext): string | null {
  if (event.aggregateType === 'stage_run') return event.aggregateId
  return agentId ? (ctx.stageOfAgent?.(agentId) ?? null) : null
}

function toolTitle(entry: TimelineEntry): string {
  const started = entry.events[0]
  const completed = entry.events.find((e) => e.type === 'agent.tool.completed')
  const state = !completed ? ' (en curso)' : completed.payload.isError === true ? ' (falló)' : ''
  return `${str(started.payload.name) ?? '?'}${toolSummary(started.payload.input)}${state}`
}

function groupTitle(name: string, count: number): string {
  switch (name) {
    case 'Read':
      return `Leídos ${count} ficheros`
    case 'Edit':
    case 'Write':
    case 'MultiEdit':
      return `Editados ${count} ficheros`
    case 'Bash':
      return `${count} comandos`
    case 'Grep':
    case 'Glob':
      return `${count} búsquedas`
    default:
      return `${name} ×${count}`
  }
}

/**
 * Timeline semántico (ADR-0001 §3.7): une el inicio y el fin de cada herramienta en una entrada,
 * pliega en ella los cambios pensando↔ejecutando que provoca, y agrupa las llamadas seguidas a la
 * misma herramienta de un mismo agente ("Leídos 14 ficheros"). Cada entrada conserva sus eventos
 * originales.
 */
export function aggregateTimeline(
  events: StoredEvent[],
  ctx: TimelineContext = {},
): TimelineEntry[] {
  const entries: TimelineEntry[] = []
  const tools = new Map<string, TimelineEntry>()
  let lastTool: TimelineEntry | null = null

  for (const event of events) {
    const toolUseId = str(event.payload.toolUseId)
    if (event.type === 'agent.tool.started' && toolUseId) {
      const entry = { ...single(event, ctx), id: `t${toolUseId}` }
      tools.set(toolUseId, entry)
      entries.push(entry)
      lastTool = entry
    } else if (event.type === 'agent.tool.completed' && toolUseId && tools.has(toolUseId)) {
      const entry = tools.get(toolUseId)!
      entry.events.push(event)
      entry.severity = maxSeverity(entry.severity, severityOf(event))
      lastTool = entry
    } else if (isToolTransition(event) && lastTool && lastTool.agentId === event.aggregateId) {
      lastTool.events.push(event)
    } else {
      entries.push(single(event, ctx))
      lastTool = null
    }
  }
  for (const entry of tools.values()) {
    entry.events.sort((a, b) => a.sequence - b.sequence)
    entry.title = toolTitle(entry)
  }
  return groupConsecutiveTools(entries)
}

function toolName(entry: TimelineEntry): string | null {
  return entry.kind === 'tool' && entry.id.startsWith('t')
    ? str(entry.events[0].payload.name)
    : null
}

function groupConsecutiveTools(entries: TimelineEntry[]): TimelineEntry[] {
  const result: TimelineEntry[] = []
  let run: TimelineEntry[] = []
  const flush = () => {
    if (run.length === 1) result.push(run[0])
    if (run.length > 1) {
      const first = run[0]
      result.push({
        ...first,
        id: `g${first.id}`,
        title: groupTitle(toolName(first)!, run.length),
        severity: run.map((e) => e.severity).reduce(maxSeverity),
        events: run.flatMap((e) => e.events),
        children: run,
      })
    }
    run = []
  }
  for (const entry of entries) {
    const name = toolName(entry)
    const previous = run.at(-1)
    if (name && previous && toolName(previous) === name && previous.agentId === entry.agentId) {
      run.push(entry)
    } else {
      flush()
      if (name) run.push(entry)
      else result.push(entry)
    }
  }
  flush()
  return result
}

export interface TimelineFilters {
  stage?: string
  agent?: string
  kind?: EventKind
  /** Severidad mínima. */
  severity?: Severity
  origin?: Origin
}

export function matchesFilters(entry: TimelineEntry, f: TimelineFilters): boolean {
  return (
    (!f.stage || entry.stageId === f.stage) &&
    (!f.agent || entry.agentId === f.agent) &&
    (!f.kind || entry.kind === f.kind) &&
    (!f.severity || SEVERITY_RANK[entry.severity] >= SEVERITY_RANK[f.severity]) &&
    (!f.origin || entry.origin === f.origin)
  )
}

/** Posición de la entrada que contiene un evento, o -1. */
export function indexOfSequence(entries: TimelineEntry[], sequence: number): number {
  return entries.findIndex((e) => e.events.some((ev) => ev.sequence === sequence))
}
