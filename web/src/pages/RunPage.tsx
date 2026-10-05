import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { api, type AgentRun } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { EventTimeline } from '../components/EventTimeline'
import { RunHeader } from '../components/run/RunHeader'
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
        <Link to="/runs">Ejecuciones</Link> /
      </p>
      <ErrorMessage error={run.error} />
      {run.data && (
        <>
          <RunHeader run={run.data} stream={state} />
          {run.data.stages.map((stage) => (
            <section key={stage.id} className="card">
              <h2>
                Fase {stage.stageKey} <StatusBadge status={stage.status} />
              </h2>
              {stage.agents.map((agent) => (
                <AgentCard key={agent.id} agent={agent} />
              ))}
            </section>
          ))}
        </>
      )}
      <h2>Timeline</h2>
      <EventTimeline events={events} />
    </>
  )
}

function AgentCard({ agent }: { agent: AgentRun }) {
  const detail = useQuery({ queryKey: ['agent', agent.id], queryFn: () => api.agent(agent.id) })

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
    </div>
  )
}
