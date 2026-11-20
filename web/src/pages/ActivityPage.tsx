import { useMemo } from 'react'
import { useRunSelection } from '../components/run/useRunSelection'
import { ActivityTable } from '../components/timeline/ActivityTable'
import { aggregateTimeline, matchesFilters } from '../components/timeline/timeline'
import { TimelineFiltersBar } from '../components/timeline/TimelineFiltersBar'
import { STREAM } from '../components/run/stream'
import { Pill } from '../components/ui/Pill'
import { useEventStream } from '../useEventStream'

/**
 * Todos los eventos del sistema en tiempo real, en una tabla con lo último arriba y scroll
 * infinito, con los filtros del timeline en la URL; los de una ejecución abren su inspector.
 */
export function ActivityPage() {
  const [selection, select] = useRunSelection()
  const { events, state } = useEventStream({})
  const entries = useMemo(() => aggregateTimeline(events), [events])
  const visible = entries.filter((e) => matchesFilters(e, selection.filters))
  const stream = STREAM[state]
  return (
    <>
      <div className="page-head">
        <h1>Actividad</h1>
        <span role="status">
          <Pill tone={stream.tone} icon={stream.icon}>
            {stream.label}
          </Pill>
        </span>
      </div>
      <TimelineFiltersBar
        filters={selection.filters}
        stages={[]}
        agents={[]}
        onChange={(filters) => select({ filters })}
      />
      <ActivityTable entries={visible} />
    </>
  )
}
