import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { api, type AgentRun } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { EventTimeline } from '../components/EventTimeline'
import { StatusBadge } from '../components/StatusBadge'
import { formatCost, formatDateTime, formatNumber } from '../format'
import { useEventStream } from '../useEventStream'

export function RunPage() {
  const { runId = '' } = useParams()
  const queryClient = useQueryClient()
  const run = useQuery({ queryKey: ['run', runId], queryFn: () => api.run(runId) })
  // Cada evento de la ejecución puede cambiar estados: se vuelve a leer el estado actual.
  const { events, state } = useEventStream({ workflowRunId: runId }, () => {
    void queryClient.invalidateQueries({ queryKey: ['run', runId] })
  })

  return (
    <>
      <p className="breadcrumbs">
        <Link to="/">Proyectos</Link> /{' '}
        {run.data && <Link to={`/work-items/${run.data.workItemId}`}>trabajo</Link>} /
      </p>
      <ErrorMessage error={run.error} />
      {run.data && (
        <>
          <h1>
            Ejecución <StatusBadge status={run.data.status} />
          </h1>
          <p className="muted">
            Creada {formatDateTime(run.data.createdAt)} · finalizada{' '}
            {formatDateTime(run.data.finishedAt)}
          </p>
          {run.data.stages.map((stage) => (
            <section key={stage.id} className="card">
              <h2>
                Fase {stage.stageKey} <StatusBadge status={stage.status} />
              </h2>
              {stage.agents.map((agent) => (
                <AgentCard key={agent.id} agent={agent} runId={runId} />
              ))}
            </section>
          ))}
        </>
      )}
      <h2>
        Timeline <span className="muted small">({streamLabel[state]})</span>
      </h2>
      <EventTimeline events={events} />
    </>
  )
}

const streamLabel = {
  connecting: 'conectando…',
  open: 'en vivo',
  reconnecting: 'reconectando…',
} as const

function AgentCard({ agent, runId }: { agent: AgentRun; runId: string }) {
  const queryClient = useQueryClient()
  const detail = useQuery({ queryKey: ['agent', agent.id], queryFn: () => api.agent(agent.id) })
  const cancel = useMutation({
    mutationFn: () => api.cancelAgent(agent.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['run', runId] })
      void queryClient.invalidateQueries({ queryKey: ['agent', agent.id] })
    },
  })
  const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(agent.status)

  return (
    <div className="agent">
      <p>
        <strong>{agent.provider}</strong> <StatusBadge status={agent.status} />{' '}
        <span className="muted">
          {agent.kind} · creado {formatDateTime(agent.createdAt)}
        </span>
      </p>
      <dl className="agent-facts small">
        <dt>Modelo</dt>
        <dd>{agent.model ?? '—'}</dd>
        <dt>Última actividad</dt>
        <dd>{formatDateTime(agent.lastActivityAt)}</dd>
        <dt>Turnos</dt>
        <dd>{formatNumber(agent.numTurns)}</dd>
        <dt>Tokens</dt>
        <dd>
          {formatNumber(agent.inputTokens)} entrada · {formatNumber(agent.outputTokens)} salida
        </dd>
        <dt>Coste</dt>
        <dd>{formatCost(agent.costUsd)}</dd>
        {agent.exitCode != null && (
          <>
            <dt>Código de salida</dt>
            <dd>{agent.exitCode}</dd>
          </>
        )}
      </dl>
      {agent.error ? <p className="error small">{agent.error}</p> : null}
      {detail.data?.prompts.map((p) => (
        <details key={p.id}>
          <summary>
            Prompt <code className="small">sha256 {p.sha256.slice(0, 12)}…</code>
          </summary>
          <pre className="prewrap">{p.content}</pre>
        </details>
      ))}
      {!terminal && (
        <button type="button" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
          Cancelar
        </button>
      )}
      <ErrorMessage error={cancel.error} />
    </div>
  )
}
