import { useEffect, useState } from 'react'
import type { StoredEvent } from './api'

export type StreamState = 'connecting' | 'open' | 'reconnecting'

/**
 * Se suscribe al stream SSE de eventos. El navegador reconecta solo y envía Last-Event-ID, así
 * que el servidor reanuda justo después del último evento recibido.
 */
export function useEventStream(
  query: { workflowRunId?: string },
  onEvent?: (event: StoredEvent) => void,
): { events: StoredEvent[]; state: StreamState } {
  const [events, setEvents] = useState<StoredEvent[]>([])
  const [state, setState] = useState<StreamState>('connecting')
  const { workflowRunId } = query

  useEffect(() => {
    setEvents([])
    setState('connecting')
    const params = new URLSearchParams()
    if (workflowRunId) params.set('workflowRunId', workflowRunId)
    const source = new EventSource(`/api/events/stream?${params}`)
    source.onopen = () => setState('open')
    source.onerror = () => setState('reconnecting')
    source.onmessage = (message) => {
      const event = JSON.parse(message.data as string) as StoredEvent
      setEvents((prev) =>
        prev.some((e) => e.sequence === event.sequence) ? prev : [...prev, event],
      )
      onEvent?.(event)
    }
    return () => source.close()
    // onEvent se lee en cada mensaje; no debe reabrir la conexión.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflowRunId])

  return { events, state }
}
