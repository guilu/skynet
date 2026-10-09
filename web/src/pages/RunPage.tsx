import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChartGantt, List, ListTree, MessagesSquare, PanelRight } from 'lucide-react'
import { Group, Panel, Separator, useDefaultLayout } from 'react-resizable-panels'
import { useEffect, useMemo, useRef } from 'react'
import { useParams } from 'react-router'
import { api, type Run } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { eventsOfAgent, toolCallsOf, type ToolCall } from '../components/run/agentEvents'
import { Conversation } from '../components/run/Conversation'
import { RunTree } from '../components/run/RunTree'
import { runSpans } from '../components/run/runSpans'
import { Waterfall } from '../components/run/Waterfall'
import { applyEvent } from '../components/run/applyEvent'
import { headlineAgent } from '../components/run/headlineAgent'
import { Inspector } from '../components/run/Inspector'
import { RunHeader } from '../components/run/RunHeader'
import { ACTIVITY_VIEWS, useRunSelection, type RunPane } from '../components/run/useRunSelection'
import { TabList, TabPanel, type TabItem } from '../components/ui/Tabs'
import { Timeline } from '../components/timeline/Timeline'
import { aggregateTimeline, indexOfSequence, matchesFilters } from '../components/timeline/timeline'
import { TimelineFiltersBar } from '../components/timeline/TimelineFiltersBar'
import { useEventStream } from '../useEventStream'
import { useMediaQuery } from '../useMediaQuery'
import { useNow } from '../useNow'

/**
 * Ejecución en tres paneles redimensionables (ADR-0001 §3.4): el árbol de fases, agentes y
 * herramientas; la actividad (conversación, cascada y eventos), y el inspector del agente elegido.
 * En el móvil, los paneles pasan a pestañas. La selección vive en la URL.
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
  const spans = useMemo(() => {
    if (!run.data) return []
    const calls = new Map<string, ToolCall[]>()
    for (const a of run.data.stages.flatMap((s) => s.agents)) {
      calls.set(a.id, toolCallsOf(eventsOfAgent(events, a.id)))
    }
    return runSpans(run.data, (id) => calls.get(id) ?? [])
  }, [run.data, events])
  const now = useNow(run.data?.finishedAt == null)
  const narrow = useMediaQuery('(max-width: 900px)')
  const panels = useDefaultLayout({ id: 'skynet.run-panels', storage: safeStorage })
  const visible = timeline.filter((e) => matchesFilters(e, selection.filters))
  const hiddenSelection =
    selection.sequence != null &&
    indexOfSequence(visible, selection.sequence) < 0 &&
    indexOfSequence(timeline, selection.sequence) >= 0
  // Sin agente en la URL (o uno que no es de esta ejecución), el de la cabecera.
  const agent =
    agents.find((a) => a.id === selection.agentId) ?? (run.data && headlineAgent(run.data))

  const selectAgent = (agentId: string) =>
    select({ agentId, toolUseId: null, sequence: null, ...(narrow && { pane: 'inspector' }) })
  const selectTool = (agentId: string, toolUseId: string) =>
    select({ agentId, toolUseId, tab: 'tools', ...(narrow && { pane: 'inspector' }) })

  const tree = run.data && (
    <section className="run-pane" aria-label="Pasos de la ejecución">
      <RunTree
        run={run.data}
        spans={spans}
        now={now}
        selectedAgentId={agent?.id}
        selectedToolUseId={selection.toolUseId}
        onSelectAgent={selectAgent}
        onSelectTool={selectTool}
      />
    </section>
  )

  const activity = (
    <section className="run-pane run-activity" aria-label="Actividad">
      <TabList
        label="Actividad"
        tabs={ACTIVITY_VIEWS.map((v) => ({
          ...v,
          icon: VIEW_ICONS[v.id],
          count: v.id === 'events' ? timeline.length : undefined,
        }))}
        selected={selection.view}
        onSelect={(view) => select({ view })}
        idPrefix="view"
        panelId="activity-panel"
      />
      <TabPanel id="activity-panel" labelledBy={`view-${selection.view}`}>
        {selection.view === 'conversation' &&
          (agent ? (
            <Conversation
              agent={agent}
              events={eventsOfAgent(events, agent.id)}
              onShowEvent={(sequence) => select({ tab: 'event', sequence })}
            />
          ) : (
            <p className="muted">Esta ejecución aún no tiene agentes.</p>
          ))}
        {selection.view === 'waterfall' && (
          <Waterfall
            spans={spans}
            now={now}
            selectedAgentId={agent?.id}
            selectedToolUseId={selection.toolUseId}
            onSelectAgent={selectAgent}
            onSelectTool={selectTool}
          />
        )}
        {selection.view === 'events' && (
          <>
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
                  ...(narrow && { pane: 'inspector' }),
                })
              }
            />
          </>
        )}
      </TabPanel>
    </section>
  )

  const inspector = agent ? (
    <Inspector
      agent={agent}
      events={eventsOfAgent(events, agent.id)}
      tab={selection.tab}
      sequence={selection.sequence}
      toolUseId={selection.toolUseId}
      onTab={(tab) => select({ tab })}
      onShowEvent={(sequence) =>
        select({ tab: 'event', sequence, ...(narrow && { pane: 'inspector' }) })
      }
    />
  ) : (
    <p className="muted">Esta ejecución aún no tiene agentes.</p>
  )

  return (
    <>
      <ErrorMessage error={run.error} />
      {run.data && (
        <>
          <RunHeader run={run.data} stream={state} />
          {narrow ? (
            <div className="run-mobile">
              <TabList
                label="Paneles de la ejecución"
                tabs={PANES}
                selected={selection.pane}
                onSelect={(pane) => select({ pane })}
                idPrefix="pane"
                panelId="pane-panel"
              />
              <TabPanel id="pane-panel" labelledBy={`pane-${selection.pane}`} className="card">
                {selection.pane === 'tree' && tree}
                {selection.pane === 'activity' && activity}
                {selection.pane === 'inspector' && inspector}
              </TabPanel>
            </div>
          ) : (
            <Group
              className="run-panels card"
              orientation="horizontal"
              defaultLayout={panels.defaultLayout}
              onLayoutChanged={panels.onLayoutChanged}
            >
              <Panel id="tree" defaultSize="26" minSize="220px" className="run-panel">
                {tree}
              </Panel>
              <Separator className="run-separator" aria-label="Ancho del árbol" />
              <Panel id="activity" defaultSize="40" minSize="320px" className="run-panel">
                {activity}
              </Panel>
              <Separator className="run-separator" aria-label="Ancho del inspector" />
              <Panel id="inspector" defaultSize="34" minSize="300px" className="run-panel">
                {inspector}
              </Panel>
            </Group>
          )}
        </>
      )}
    </>
  )
}

const VIEW_ICONS = { conversation: MessagesSquare, waterfall: ChartGantt, events: List }

const PANES: TabItem<RunPane>[] = [
  { id: 'tree', label: 'Pasos', icon: ListTree },
  { id: 'activity', label: 'Actividad', icon: MessagesSquare },
  { id: 'inspector', label: 'Detalle', icon: PanelRight },
]

/** El tamaño de los paneles se recuerda en este navegador; sin almacenamiento, no pasa nada. */
const safeStorage = {
  getItem: (key: string) => {
    try {
      return localStorage.getItem(key)
    } catch {
      return null
    }
  },
  setItem: (key: string, value: string) => {
    try {
      localStorage.setItem(key, value)
    } catch {
      // Navegación privada o almacenamiento lleno: el tamaño no se recuerda.
    }
  },
}
