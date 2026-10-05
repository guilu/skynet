import type { StoredEvent } from '../api'
import { describeEvent, formatTime } from '../format'

/** Timeline de eventos (§13.4): elegir una entrada abre su evento original en el inspector. */
export function EventTimeline({
  events,
  selected,
  onSelect,
  canSelect = () => true,
}: {
  events: StoredEvent[]
  selected: number | null
  onSelect: (event: StoredEvent) => void
  canSelect?: (event: StoredEvent) => boolean
}) {
  if (events.length === 0) {
    return <p className="muted">Sin eventos todavía.</p>
  }
  return (
    <ol className="timeline">
      {events.map((e) => {
        const row = (
          <>
            <span className="timeline-time">{formatTime(e.occurredAt)}</span>
            <code className="timeline-type">{e.type}</code>
            <span>{describeEvent(e.type, e.payload)}</span>
          </>
        )
        return (
          <li key={e.sequence}>
            {canSelect(e) ? (
              <button
                type="button"
                className="timeline-row"
                aria-current={selected === e.sequence ? 'true' : undefined}
                onClick={() => onSelect(e)}
              >
                {row}
              </button>
            ) : (
              <div className="timeline-row">{row}</div>
            )}
          </li>
        )
      })}
    </ol>
  )
}
