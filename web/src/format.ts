const dateTime = new Intl.DateTimeFormat('es-ES', {
  dateStyle: 'short',
  timeStyle: 'medium',
})
const time = new Intl.DateTimeFormat('es-ES', { timeStyle: 'medium' })

export const formatDateTime = (iso: string | null) => (iso ? dateTime.format(new Date(iso)) : '—')
export const formatTime = (iso: string) => time.format(new Date(iso))

const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pendiente',
  READY: 'Preparada',
  STARTING: 'Arrancando',
  RUNNING: 'En curso',
  QUEUED: 'En cola',
  THINKING: 'Pensando',
  EXECUTING: 'Ejecutando',
  WAITING_FOR_INPUT: 'Esperando información',
  WAITING_FOR_APPROVAL: 'Esperando aprobación',
  UNRESPONSIVE: 'Sin actividad',
  SUCCEEDED: 'Completada',
  COMPLETED: 'Completado',
  FAILED: 'Fallida',
  CANCELLED: 'Cancelada',
  SKIPPED: 'Omitida',
  OPEN: 'Abierto',
  CLOSED: 'Cerrado',
}

export const statusLabel = (status: string) => STATUS_LABELS[status] ?? status

export type StatusTone = 'neutral' | 'active' | 'ok' | 'bad' | 'warn'

export function statusTone(status: string): StatusTone {
  if (['SUCCEEDED', 'COMPLETED'].includes(status)) return 'ok'
  if (['FAILED'].includes(status)) return 'bad'
  if (['WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL', 'UNRESPONSIVE'].includes(status)) return 'warn'
  if (['RUNNING', 'STARTING', 'THINKING', 'EXECUTING'].includes(status)) return 'active'
  return 'neutral'
}

/** Resumen legible de un evento para el timeline. */
export function describeEvent(type: string, payload: Record<string, unknown>): string {
  const p = (k: string) => String(payload[k] ?? '')
  switch (type) {
    case 'project.created':
      return `Proyecto ${p('key')} creado`
    case 'repository.registered':
      return `Repositorio ${p('name')} registrado (${p('localPath')})`
    case 'workitem.created':
      return `Trabajo ${p('key')} creado: ${p('title')}`
    case 'workflow.started':
      return `Ejecución iniciada (workflow ${p('definition')})`
    case 'stage.ready':
      return `Fase ${p('stageKey')} preparada (intento ${p('attempt')})`
    case 'agent.spawned':
      return `Agente ${p('provider')} en cola`
    case 'agent.status.changed':
    case 'stage.status.changed':
    case 'workflow.status.changed':
      return `${statusLabel(p('previousStatus'))} → ${statusLabel(p('status'))}`
    default:
      return type
  }
}
