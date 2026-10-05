import { useNavigate } from 'react-router'
import { EventTimeline } from '../components/EventTimeline'
import { eventLink } from '../components/run/eventLink'
import { useEventStream } from '../useEventStream'

/** Todos los eventos del sistema en tiempo real; los de una ejecución abren su inspector. */
export function ActivityPage() {
  const navigate = useNavigate()
  const { events, state } = useEventStream({})
  return (
    <>
      <h1>
        Actividad <span className="muted small">({state === 'open' ? 'en vivo' : state})</span>
      </h1>
      <EventTimeline
        events={[...events].reverse()}
        selected={null}
        canSelect={(e) => eventLink(e) != null}
        onSelect={(e) => void navigate(eventLink(e)!)}
      />
    </>
  )
}
