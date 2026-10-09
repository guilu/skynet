import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, type ReactNode } from 'react'
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
import { Pill } from '../ui/Pill'
import { AgentActions } from './AgentActions'
import { ArtifactsTab } from './ArtifactsTab'
import { agentOutcome, toolCallsOf, type ToolCall } from './agentEvents'
import { JsonView } from './JsonView'
import { CostTab } from './CostTab'
import { LongText } from './LongText'
import { INSPECTOR_TABS, type InspectorTab } from './useRunSelection'
import { VerificationTab } from './VerificationTab'
import { Button } from '../ui/Button'
import { TabList, TabPanel } from '../ui/Tabs'

interface Props {
  agent: AgentRun
  /** Eventos del agente, ya filtrados. */
  events: StoredEvent[]
  tab: InspectorTab
  sequence: number | null
  /** Llamada elegida en el árbol o en la cascada: se abre en Herramientas. */
  toolUseId: string | null
  onTab: (tab: InspectorTab) => void
  onShowEvent: (sequence: number) => void
}

/**
 * Inspector del agente elegido (ADR-0001 §3.6). El texto de los agentes se pinta siempre como
 * texto: React lo escapa y aquí no se usa HTML crudo en ningún sitio.
 */
export function Inspector({ agent, events, tab, sequence, toolUseId, onTab, onShowEvent }: Props) {
  return (
    <section className="run-inspector" aria-label="Inspector del agente">
      <AgentActions agent={agent} />
      <TabList
        label="Detalle del agente"
        tabs={INSPECTOR_TABS}
        selected={tab}
        onSelect={onTab}
        idPrefix="tab"
        panelId="inspector-panel"
      />
      <TabPanel id="inspector-panel" labelledBy={`tab-${tab}`}>
        {tab === 'summary' && <Summary agent={agent} events={events} />}
        {tab === 'prompt' && <Prompts agentId={agent.id} />}
        {tab === 'tools' && (
          <Tools events={events} selected={toolUseId} onShowEvent={onShowEvent} />
        )}
        {tab === 'artifacts' && <ArtifactsTab agent={agent} />}
        {tab === 'verification' && <VerificationTab agent={agent} events={events} />}
        {tab === 'cost' && <CostTab agent={agent} />}
        {tab === 'event' && <OriginalEvent sequence={sequence} />}
      </TabPanel>
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
  selected,
  onShowEvent,
}: {
  events: StoredEvent[]
  selected: string | null
  onShowEvent: (sequence: number) => void
}) {
  const calls = toolCallsOf(events)
  const selectedRef = useRef<HTMLLIElement>(null)
  // La llamada elegida en el árbol o en la cascada se abre y se trae a la vista.
  useEffect(() => {
    selectedRef.current?.scrollIntoView({ block: 'nearest' })
  }, [selected])
  if (calls.length === 0) return <p className="muted">Sin herramientas todavía.</p>
  return (
    <ol className="tool-calls">
      {calls.map((call) => (
        <li
          key={call.toolUseId}
          ref={call.toolUseId === selected ? selectedRef : undefined}
          className={call.toolUseId === selected ? 'selected' : undefined}
        >
          <details open={call.toolUseId === selected || undefined}>
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
            <JsonView value={call.input} label="Entrada" />
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
              <Button
                type="button"
                variant="link"
                onClick={() => onShowEvent(call.startedSequence)}
              >
                Evento de inicio #{call.startedSequence}
              </Button>
              {call.completedSequence != null && (
                <>
                  {' · '}
                  <Button
                    type="button"
                    variant="link"
                    onClick={() => onShowEvent(call.completedSequence!)}
                  >
                    Evento de resultado #{call.completedSequence}
                  </Button>
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
  return <Pill tone={call.isError ? 'bad' : 'ok'}>{call.isError ? 'Error' : 'Hecha'}</Pill>
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
        Elige un evento en Eventos, o «Ver evento» en Conversación o Herramientas.
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
      <JsonView value={event.data} label={`Evento #${event.data.sequence}`} />
    </>
  )
}
