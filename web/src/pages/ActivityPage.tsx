import { EventTimeline } from '../components/EventTimeline'
import { useEventStream } from '../useEventStream'

/** Todos los eventos del sistema en tiempo real. */
export function ActivityPage() {
  const { events, state } = useEventStream({})
  return (
    <>
      <h1>
        Actividad <span className="muted small">({state === 'open' ? 'en vivo' : state})</span>
      </h1>
      <EventTimeline events={[...events].reverse()} />
    </>
  )
}
