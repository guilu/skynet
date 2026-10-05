import { useNavigate } from 'react-router'
import { useMemo } from 'react'
import { eventLink } from '../components/run/eventLink'
import { Timeline } from '../components/timeline/Timeline'
import { aggregateTimeline } from '../components/timeline/timeline'
import { useEventStream } from '../useEventStream'

/**
 * Todos los eventos del sistema en tiempo real, los últimos abajo; los de una ejecución abren su
 * inspector.
 */
export function ActivityPage() {
  const navigate = useNavigate()
  const { events, state } = useEventStream({})
  const entries = useMemo(() => aggregateTimeline(events), [events])
  return (
    <>
      <h1>
        Actividad <span className="muted small">({state === 'open' ? 'en vivo' : state})</span>
      </h1>
      <Timeline
        entries={entries}
        selected={null}
        onSelect={(e) => {
          const link = eventLink(e)
          if (link) void navigate(link)
        }}
      />
    </>
  )
}
