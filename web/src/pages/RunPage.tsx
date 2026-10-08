import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef } from 'react'
import { useParams } from 'react-router'
import { api, type Run } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { AgentNav } from '../components/run/AgentNav'
import { eventsOfAgent } from '../components/run/agentEvents'
import { applyEvent } from '../components/run/applyEvent'
import { headlineAgent } from '../components/run/headlineAgent'
import { Inspector } from '../components/run/Inspector'
import { RunHeader } from '../components/run/RunHeader'
import { useRunSelection } from '../components/run/useRunSelection'
import { Timeline } from '../components/timeline/Timeline'
import { aggregateTimeline, indexOfSequence, matchesFilters } from '../components/timeline/timeline'
import { TimelineFiltersBar } from '../components/timeline/TimelineFiltersBar'
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
  const refetchTimer = useRef<{ id: ReturnType<typeof setTimeout>; at: number } | null>(null)
  useEffect(() => () => clearTimeout(refetchTimer.current?.id), [])

  // Releer la ejecución, agrupando las peticiones: varias seguidas acaban en una sola.
  function scheduleRefetch(delayMs: number) {
    const at = Date.now() + delayMs
    if (refetchTimer.current && refetchTimer.current.at <= at) return
    clearTimeout(refetchTimer.current?.id)
    const id = setTimeout(() => {
      refetchTimer.current = null
      void queryClient.invalidateQueries({ queryKey: ['run', runId] })
    }, delayMs)
    refetchTimer.current = { id, at }
  }

  // Cada evento se aplica a la vista en caché; solo se relee lo que el evento no trae.
  const { events, state } = useEventStream({ workflowRunId: runId }, (event) => {
    // Verificaciones y artefactos van aparte de la vista de la ejecución: se releen sus listas.
    if (event.aggregateType === 'verification_run' || event.aggregateType === 'artifact') {
      const agentId = event.payload.agentRunId
      const key = event.aggregateType === 'artifact' ? 'artifacts' : 'verifications'
      void queryClient.invalidateQueries({
        queryKey: typeof agentId === 'string' ? [key, agentId] : [key],
      })
      if (key === 'verifications' && event.type === 'agent.verification.completed') {
        void queryClient.invalidateQueries({ queryKey: ['artifacts'] })
      }
      return
    }
    let refetch = 'now'
    queryClient.setQueryData<Run>(['run', runId], (current) => {
      if (!current) return current
      const result = applyEvent(current, event)
      refetch = result.refetch
      return result.run
    })
    if (refetch === 'now') scheduleRefetch(0)
    else if (refetch === 'soon') scheduleRefetch(3000)
  })

  const agents = run.data?.stages.flatMap((s) => s.agents) ?? []
  const stageOfAgent = useMemo(() => {
    const map = new Map<string, string>()
    run.data?.stages.forEach((s) => s.agents.forEach((a) => map.set(a.id, s.id)))
    return map
  }, [run.data])
  const timeline = useMemo(
    () => aggregateTimeline(events, { stageOfAgent: (id) => stageOfAgent.get(id) }),
    [events, stageOfAgent],
  )
  const visible = timeline.filter((e) => matchesFilters(e, selection.filters))
  const hiddenSelection =
    selection.sequence != null &&
    indexOfSequence(visible, selection.sequence) < 0 &&
    indexOfSequence(timeline, selection.sequence) >= 0
  // Sin agente en la URL (o uno que no es de esta ejecución), el de la cabecera.
  const agent =
    agents.find((a) => a.id === selection.agentId) ?? (run.data && headlineAgent(run.data))

  return (
    <>
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
        <TimelineFiltersBar
          filters={selection.filters}
          stages={run.data?.stages.map((s) => ({ value: s.id, label: s.stageKey })) ?? []}
          agents={agents.map((a, i) => ({
            value: a.id,
            label: `${i + 1}. ${a.provider} ${a.kind}`,
          }))}
          onChange={(filters) => select({ filters })}
        />
        {hiddenSelection && (
          <p className="attention small" role="status">
            El evento #{selection.sequence} no se ve con estos filtros.
          </p>
        )}
        <Timeline
          entries={visible}
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
