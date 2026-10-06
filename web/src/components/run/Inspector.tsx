import { useQuery } from '@tanstack/react-query'
import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { api, type AgentRun, type StoredEvent } from '../../api'
import {
  elapsed,
  formatCost,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatTime,
  toolSummary,
} from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { AgentActions } from './AgentActions'
import { agentOutcome, toolCallsOf, type ToolCall } from './agentEvents'
import { Conversation } from './Conversation'
import { LongText } from './LongText'
import { INSPECTOR_TABS, type InspectorTab } from './useRunSelection'

interface Props {
  agent: AgentRun
  /** Eventos del agente, ya filtrados. */
  events: StoredEvent[]
  tab: InspectorTab
  sequence: number | null
  onTab: (tab: InspectorTab) => void
  onShowEvent: (sequence: number) => void
}

/**
 * Inspector del agente elegido (ADR-0001 §3.6). El texto de los agentes se pinta siempre como
 * texto: React lo escapa y aquí no se usa HTML crudo en ningún sitio.
 */
export function Inspector({ agent, events, tab, sequence, onTab, onShowEvent }: Props) {
  const tabs = useRef<(HTMLButtonElement | null)[]>([])
  const current = INSPECTOR_TABS.findIndex((t) => t.id === tab)

  // Patrón de pestañas de WAI-ARIA: las flechas mueven el foco y activan la pestaña.
  function onKeyDown(e: KeyboardEvent) {
    const last = INSPECTOR_TABS.length - 1
    const target = {
      ArrowRight: current === last ? 0 : current + 1,
      ArrowLeft: current === 0 ? last : current - 1,
      Home: 0,
      End: last,
    }[e.key]
    if (target === undefined) return
    e.preventDefault()
    onTab(INSPECTOR_TABS[target].id)
    tabs.current[target]?.focus()
  }

  return (
    <section className="run-inspector card" aria-label="Inspector del agente">
      <AgentActions agent={agent} />
      <div role="tablist" aria-label="Detalle del agente" className="tabs" onKeyDown={onKeyDown}>
        {INSPECTOR_TABS.map((t, i) => (
          <button
            key={t.id}
            ref={(el) => {
              tabs.current[i] = el
            }}
            type="button"
            role="tab"
            id={`tab-${t.id}`}
            aria-selected={t.id === tab}
            aria-controls="inspector-panel"
            tabIndex={t.id === tab ? 0 : -1}
            onClick={() => onTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id="inspector-panel" aria-labelledby={`tab-${tab}`} tabIndex={0}>
        {tab === 'summary' && <Summary agent={agent} events={events} />}
        {tab === 'prompt' && <Prompts agentId={agent.id} />}
        {tab === 'conversation' && (
          <Conversation agent={agent} events={events} onShowEvent={onShowEvent} />
        )}
        {tab === 'tools' && <Tools events={events} onShowEvent={onShowEvent} />}
        {tab === 'event' && <OriginalEvent sequence={sequence} />}
      </div>
    </section>
  )
}

function Summary({ agent, events }: { agent: AgentRun; events: StoredEvent[] }) {
  const { branch, result } = agentOutcome(events)
  const facts: [string, ReactNode][] = [
    ['Estado', <StatusBadge key="s" status={agent.status} />],
    ['Proveedor', `${agent.provider} · ${agent.kind}`],
    ['Modelo', agent.model ?? '—'],
    ['Sesión', agent.providerSessionId ? <code key="c">{agent.providerSessionId}</code> : '—'],
    ['Rama', branch ? <code key="b">{branch}</code> : '—'],
    ['Creado', formatDateTime(agent.createdAt)],
    ['Última actividad', formatDateTime(agent.lastActivityAt)],
    [
      'Duración',
      formatDuration(
        agent.startedAt
          ? elapsed(agent.startedAt, agent.finishedAt ?? agent.lastActivityAt, 0)
          : null,
      ),
    ],
    ['Turnos', formatNumber(agent.numTurns)],
    [
      'Tokens',
      `${formatNumber(agent.inputTokens)} entrada · ${formatNumber(agent.outputTokens)} salida · ` +
        `caché ${formatNumber(agent.cacheReadTokens)} / ${formatNumber(agent.cacheCreationTokens)}`,
    ],
    ['Coste', formatCost(agent.costUsd)],
    ['Resultado', agent.resultSubtype ?? '—'],
  ]
  if (agent.exitCode != null) facts.push(['Código de salida', String(agent.exitCode)])

  return (
    <>
      <dl className="agent-facts small">
        {facts.map(([label, value]) => (
          <div key={label} className="fact">
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {agent.error && <p className="error small">{agent.error}</p>}
      {result && (
        <>
          <h3>Respuesta final</h3>
          <LongText text={result} />
        </>
      )}
    </>
  )
}

function Prompts({ agentId }: { agentId: string }) {
  const detail = useQuery({ queryKey: ['agent', agentId], queryFn: () => api.agent(agentId) })
  if (detail.error) return <ErrorMessage error={detail.error} />
  if (!detail.data) return <p className="muted">Cargando…</p>
  if (detail.data.prompts.length === 0) return <p className="muted">Sin prompt registrado.</p>
  return (
    <>
      {detail.data.prompts.map((p) => (
        <article key={p.id}>
          <p className="muted small">
            {p.role} · {formatDateTime(p.createdAt)} · <code>sha256 {p.sha256.slice(0, 12)}…</code>
          </p>
          <LongText text={p.content} />
        </article>
      ))}
    </>
  )
}

function Tools({
  events,
  onShowEvent,
}: {
  events: StoredEvent[]
  onShowEvent: (sequence: number) => void
}) {
  const calls = toolCallsOf(events)
  if (calls.length === 0) return <p className="muted">Sin herramientas todavía.</p>
  return (
    <ol className="tool-calls">
      {calls.map((call) => (
        <li key={call.toolUseId}>
          <details>
            <summary>
              <ToolState call={call} /> <strong>{call.name}</strong>
              <span className="muted">{toolSummary(call.input)}</span>
              <span className="muted small">
                {' '}
                · {formatTime(call.startedAt)}
                {call.completedAt &&
                  ` · ${formatDuration(elapsed(call.startedAt, call.completedAt, 0))}`}
              </span>
            </summary>
            <h3>Entrada</h3>
            <LongText text={JSON.stringify(call.input, null, 2)} code />
            <h3>Salida</h3>
            {call.output == null ? (
              <p className="muted small">{call.completedSequence ? 'Sin salida.' : 'En curso…'}</p>
            ) : (
              <LongText text={call.output} code />
            )}
            {call.outputTruncated && (
              <p className="muted small">El runner recortó la salida original a 16 KB.</p>
            )}
            <p className="small">
              <button
                type="button"
                className="link"
                onClick={() => onShowEvent(call.startedSequence)}
              >
                Evento de inicio #{call.startedSequence}
              </button>
              {call.completedSequence != null && (
                <>
                  {' · '}
                  <button
                    type="button"
                    className="link"
                    onClick={() => onShowEvent(call.completedSequence!)}
                  >
                    Evento de resultado #{call.completedSequence}
                  </button>
                </>
              )}
            </p>
          </details>
        </li>
      ))}
    </ol>
  )
}

function ToolState({ call }: { call: ToolCall }) {
  if (call.completedSequence == null) return <StatusBadge status="EXECUTING" />
  return (
    <span className={`badge badge-${call.isError ? 'bad' : 'ok'}`}>
      <span aria-hidden="true">{call.isError ? '✕' : '✓'}</span> {call.isError ? 'Error' : 'Hecha'}
    </span>
  )
}

/** Evento tal como está guardado (ya redactado), leído de `GET /api/events/{sequence}`. */
function OriginalEvent({ sequence }: { sequence: number | null }) {
  const event = useQuery({
    queryKey: ['event', sequence],
    queryFn: () => api.event(sequence!),
    enabled: sequence != null,
    staleTime: Infinity,
  })
  if (sequence == null) {
    return (
      <p className="muted">
        Elige un evento en el timeline, o «Ver evento» en Conversación o Herramientas.
      </p>
    )
  }
  if (event.error) return <ErrorMessage error={event.error} />
  if (!event.data) return <p className="muted">Cargando evento #{sequence}…</p>
  return (
    <>
      <p className="small">
        <code>{event.data.type}</code> · #{event.data.sequence} ·{' '}
        {formatDateTime(event.data.occurredAt)}
      </p>
      <LongText text={JSON.stringify(event.data, null, 2)} code />
    </>
  )
}
