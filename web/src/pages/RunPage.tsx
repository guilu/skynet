import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { EventTimeline } from '../components/EventTimeline'
import { AgentNav } from '../components/run/AgentNav'
import { eventsOfAgent } from '../components/run/agentEvents'
import { headlineAgent } from '../components/run/headlineAgent'
import { Inspector } from '../components/run/Inspector'
import { RunHeader } from '../components/run/RunHeader'
import { useRunSelection } from '../components/run/useRunSelection'
import { useEventStream } from '../useEventStream'

/**
 * Ejecución en tres paneles (ADR-0001 §3.4): fases y agentes, inspector del agente elegido y
 * timeline. La selección vive en la URL.
 */
export function RunPage() {
  const { runId = '' } = useParams()
  const queryClient = useQueryClient()
  const [selection, select] = useRunSelection()
  const run = useQuery({ queryKey: ['run', runId], queryFn: () => api.run(runId) })
  // Cada evento de la ejecución puede cambiar estados: se vuelve a leer el estado actual.
  const { events, state } = useEventStream({ workflowRunId: runId }, () => {
    void queryClient.invalidateQueries({ queryKey: ['run', runId] })
  })

  const agents = run.data?.stages.flatMap((s) => s.agents) ?? []
  // Sin agente en la URL (o uno que no es de esta ejecución), el de la cabecera.
  const agent =
    agents.find((a) => a.id === selection.agentId) ?? (run.data && headlineAgent(run.data))

  return (
    <>
      <p className="breadcrumbs">
        <Link to="/runs">Ejecuciones</Link> /
      </p>
      <ErrorMessage error={run.error} />
      {run.data && (
        <>
          <RunHeader run={run.data} stream={state} />
          <div className="run-layout">
            <AgentNav
              run={run.data}
              selectedId={agent?.id}
              onSelect={(agentId) => select({ agentId, sequence: null })}
            />
            {agent ? (
              <Inspector
                agent={agent}
                events={eventsOfAgent(events, agent.id)}
                tab={selection.tab}
                sequence={selection.sequence}
                onTab={(tab) => select({ tab })}
                onShowEvent={(sequence) => select({ tab: 'event', sequence })}
              />
            ) : (
              <p className="muted card">Esta ejecución aún no tiene agentes.</p>
            )}
          </div>
        </>
      )}
      <section className="run-timeline card" aria-labelledby="timeline-title">
        <h2 id="timeline-title">Timeline</h2>
        <EventTimeline
          events={events}
          selected={selection.sequence}
          onSelect={(e) =>
            select({
              agentId: e.aggregateType === 'agent_run' ? e.aggregateId : undefined,
              tab: 'event',
              sequence: e.sequence,
            })
          }
        />
      </section>
    </>
  )
}
