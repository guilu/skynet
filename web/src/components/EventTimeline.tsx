import { useState } from 'react'
import type { StoredEvent } from '../api'
import { describeEvent, formatTime } from '../format'

/** Timeline semántico (§13.4): cada entrada se puede abrir para ver el evento original. */
export function EventTimeline({ events }: { events: StoredEvent[] }) {
  const [open, setOpen] = useState<number | null>(null)
  if (events.length === 0) {
    return <p className="muted">Sin eventos todavía.</p>
  }
  return (
    <ol className="timeline">
      {events.map((e) => (
        <li key={e.sequence}>
          <button
            type="button"
            className="timeline-row"
            aria-expanded={open === e.sequence}
            onClick={() => setOpen(open === e.sequence ? null : e.sequence)}
          >
            <span className="timeline-time">{formatTime(e.occurredAt)}</span>
            <code className="timeline-type">{e.type}</code>
            <span>{describeEvent(e.type, e.payload)}</span>
          </button>
          {open === e.sequence && <pre className="json">{JSON.stringify(e, null, 2)}</pre>}
        </li>
      ))}
    </ol>
  )
}
