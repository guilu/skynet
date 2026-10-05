import type { StoredEvent } from '../../api'

/** Enlace a la página de la ejecución con el evento abierto en el inspector. */
export function eventLink(event: StoredEvent): string | null {
  if (event.workflowRunId == null) return null
  const params = new URLSearchParams()
  if (event.aggregateType === 'agent_run') params.set('agent', event.aggregateId)
  params.set('tab', 'event')
  params.set('seq', String(event.sequence))
  return `/runs/${event.workflowRunId}?${params}`
}
