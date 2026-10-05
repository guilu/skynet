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
  ONLINE: 'En línea',
  STALE: 'Sin latido',
}

export const statusLabel = (status: string) => STATUS_LABELS[status] ?? status

export type StatusTone = 'neutral' | 'active' | 'ok' | 'bad' | 'warn'

export function statusTone(status: string): StatusTone {
  if (['SUCCEEDED', 'COMPLETED', 'ONLINE'].includes(status)) return 'ok'
  if (['FAILED'].includes(status)) return 'bad'
  if (['WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL', 'UNRESPONSIVE', 'STALE'].includes(status))
    return 'warn'
  if (['RUNNING', 'STARTING', 'THINKING', 'EXECUTING'].includes(status)) return 'active'
  return 'neutral'
}

/** Icono de cada tono: el estado nunca se comunica solo con el color. */
export const TONE_ICONS: Record<StatusTone, string> = {
  neutral: '○',
  active: '●',
  ok: '✓',
  bad: '✕',
  warn: '!',
}

const TERMINAL = ['SUCCEEDED', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED']
export const isTerminal = (status: string) => TERMINAL.includes(status)

/** Duración legible: «45 s», «3 min 05 s», «1 h 02 min». */
export function formatDuration(ms: number | null): string {
  if (ms == null || ms < 0) return '—'
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) return `${h} h ${String(m).padStart(2, '0')} min`
  if (m > 0) return `${m} min ${String(s).padStart(2, '0')} s`
  return `${s} s`
}

/** Tiempo entre dos instantes; sin fin, hasta `now`. */
export function elapsed(start: string | null, end: string | null, now: number): number | null {
  if (!start) return null
  return (end ? new Date(end).getTime() : now) - new Date(start).getTime()
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
    case 'agent.workspace.ready':
      return `Worktree listo en la rama ${p('branch')}`
    case 'agent.session.started':
      return `Sesión iniciada${payload.model ? ` con ${p('model')}` : ''}`
    case 'agent.message.received':
      return `Mensaje: ${truncate(p('text'), 120)}`
    case 'agent.tool.started':
      return `Herramienta ${p('name')}${toolSummary(payload.input)}`
    case 'agent.tool.completed':
      return `Herramienta ${p('name')} ${payload.isError ? 'falló' : 'terminada'}`
    case 'agent.file.changed':
      return `Fichero ${p('path')} ${p('change') === 'create' ? 'creado' : 'modificado'}`
    case 'agent.permission.denied':
      return `Permiso denegado: ${p('toolName')}`
    case 'agent.vcs.changed':
      return `Git: ${p('kind')}${payload.branch ? ` (${p('branch')})` : ''}`
    case 'agent.rate.limit':
      return `Límite de uso: ${p('status')}`
    case 'agent.result':
      return `Resultado ${p('subtype')} · ${p('numTurns')} turnos${
        payload.costUsdCumulative != null
          ? ` · ${formatCost(Number(payload.costUsdCumulative))}`
          : ''
      }`
    case 'agent.process.exited':
      return payload.error
        ? `Proceso terminado: ${p('error')}`
        : `Proceso terminado (código ${p('exitCode')}${payload.signal ? `, ${p('signal')}` : ''})`
    case 'agent.status.changed':
    case 'stage.status.changed':
    case 'workflow.status.changed':
      return `${statusLabel(p('previousStatus'))} → ${statusLabel(p('status'))}`
    default:
      return type
  }
}

export const formatCost = (usd: number | null) =>
  usd == null ? '—' : `${usd.toLocaleString('es-ES', { maximumFractionDigits: 4 })} US$`

export const formatNumber = (n: number | null) => (n == null ? '—' : n.toLocaleString('es-ES'))

export const truncate = (text: string, max: number) =>
  text.length > max ? `${text.slice(0, max - 1)}…` : text

/** Lo más útil de la entrada de una herramienta: el comando, el fichero o el patrón. */
export function toolSummary(input: unknown): string {
  if (input == null || typeof input !== 'object') return ''
  const i = input as Record<string, unknown>
  const value = i.command ?? i.file_path ?? i.pattern ?? i.path ?? i.url
  return typeof value === 'string' ? `: ${truncate(value, 100)}` : ''
}
