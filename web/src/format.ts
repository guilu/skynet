const dateTime = new Intl.DateTimeFormat('es-ES', {
  dateStyle: 'short',
  timeStyle: 'medium',
})
const time = new Intl.DateTimeFormat('es-ES', { timeStyle: 'medium' })

export const formatDateTime = (iso: string | null) => (iso ? dateTime.format(new Date(iso)) : '—')
export const formatTime = (iso: string) => time.format(new Date(iso))

const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pendiente',
  READY: 'Lista',
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
  PASSED: 'Pasa',
  ERROR: 'Error',
  INTERRUPTED: 'Sin respuesta',
  CANCELLING: 'Cancelando',
}

export const statusLabel = (status: string) => STATUS_LABELS[status] ?? status

export type StatusTone = 'neutral' | 'active' | 'ok' | 'bad' | 'warn'

export function statusTone(status: string): StatusTone {
  if (['SUCCEEDED', 'COMPLETED', 'ONLINE', 'PASSED'].includes(status)) return 'ok'
  if (['FAILED', 'ERROR'].includes(status)) return 'bad'
  if (
    ['WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL', 'UNRESPONSIVE', 'STALE', 'CANCELLING'].includes(
      status,
    )
  )
    return 'warn'
  if (['RUNNING', 'STARTING', 'THINKING', 'EXECUTING'].includes(status)) return 'active'
  return 'neutral'
}

const TERMINAL = ['SUCCEEDED', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED']
export const isTerminal = (status: string) => TERMINAL.includes(status)

/** Worktree en el que aún se puede trabajar: preparado, sin eliminar y sin eliminación pedida. */
export const workspaceUsable = (
  workspace: { removedAt: string | null; cleanupRequestedAt: string | null } | null,
) => workspace != null && !workspace.removedAt && !workspace.cleanupRequestedAt

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
    case 'stage.pending': {
      const deps = Array.isArray(payload.dependsOn) ? payload.dependsOn.map(String) : []
      return `Fase ${p('stageKey')} en espera${deps.length ? ` de ${deps.join(', ')}` : ''}`
    }
    case 'stage.ready':
      return `Fase ${p('stageKey')} preparada (intento ${p('attempt')})`
    case 'stage.skipped':
      return payload.reason === 'condition-false'
        ? `Fase ${p('stageKey')} omitida: su condición ${p('condition')} es falsa`
        : `Fase ${p('stageKey')} omitida: no se ejecutó una fase de la que depende`
    case 'stage.condition.evaluated':
      return `Condición de ${p('stageKey')}: ${p('condition')} es ${
        payload.result ? 'verdadera' : 'falsa'
      }`
    case 'run.resumed':
      return `${p('actor')} reintentó la fase ${p('stageKey')} (intento ${p('attempt')})`
    case 'stage.retry.scheduled':
      return `Fase ${p('stageKey')}: se reintenta (intento ${p('attempt')}${
        Number(payload.attempt) <= Number(payload.maxAttempts) ? ` de ${p('maxAttempts')}` : ''
      }) en ${formatDuration(Number(payload.delaySeconds ?? 0) * 1000)}`
    case 'stage.timed.out':
      return `Fase ${p('stageKey')}: se acabó su tiempo (intento ${p('attempt')})`
    case 'workflow.cancel.requested':
      return payload.reason === 'parent-cancelled'
        ? 'Cancelación pedida: se canceló la ejecución que la lanzó'
        : 'Cancelación pedida'
    case 'agent.cancel.requested':
      return payload.reason === 'stage-timeout'
        ? 'Parada pedida al runner: se acabó el tiempo de su fase'
        : 'Cancelación pedida al runner'
    case 'agent.cancel.forced':
      return 'El runner no confirmó la cancelación: se da por cancelado'
    case 'stage.start.failed':
      return `La fase ${p('stageKey')} no pudo arrancar: ${p('error')}`
    case 'agent.spawned':
      return `Agente ${p('provider')} en cola`
    case 'agent.workspace.ready':
      return `Worktree listo en la rama ${p('branch')}`
    case 'agent.workspace.cleanup.requested':
      return `Eliminación del worktree pedida (${
        payload.trigger === 'retention' ? 'retención' : 'desde la web'
      })`
    case 'agent.workspace.removed':
      return payload.error
        ? `No se pudo eliminar el worktree: ${p('error')}`
        : `Worktree eliminado (${p('path')})`
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
    case 'agent.rate_limit':
      return `Límite de uso: ${p('status')}`
    case 'agent.quota':
      return describeQuota(payload)
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
    case 'repository.verification.configured':
      return payload.validationCommand
        ? `Verificación configurada: ${truncate(p('validationCommand'), 100)}`
        : 'Verificación desactivada'
    case 'verification.queued':
      return `Verificación ${p('trigger') === 'MANUAL' ? 'manual' : 'automática'} en cola`
    case 'agent.verification.started':
      return `Verificación iniciada: ${truncate(p('command'), 100)}`
    case 'agent.verification.completed':
      return describeVerification(payload)
    case 'artifact.created':
      return `Artefacto ${p('name')} (${formatBytes(Number(payload.size ?? 0))})${
        payload.truncated ? ', recortado' : ''
      }`
    case 'approval.requested':
      return `${p('stageKey')} pide aprobar «${p('action')}»${
        Number(payload.round) > 1 ? ` (ronda ${p('round')})` : ''
      }`
    case 'approval.granted':
      return `${p('actor')} aprobó ${p('stageKey')}`
    case 'approval.rejected':
      return `${p('actor')} rechazó ${p('stageKey')}: ${truncate(p('comment'), 120)}`
    case 'approval.changes_requested':
      return `${p('actor')} pidió cambios a ${p('reviseStageKey')}: ${truncate(p('comment'), 120)}`
    case 'approval.commented':
      return `${p('actor')} comentó ${p('stageKey')}: ${truncate(p('comment'), 120)}`
    case 'approval.expired':
      return `La aprobación de ${p('stageKey')} caducó sin decisión`
    case 'approval.cancelled':
      return `Aprobación de ${p('stageKey')} cancelada`
    case 'approval.correction.failed':
      return payload.cancelled
        ? `La corrección de ${p('reviseStageKey')} se canceló`
        : `La corrección de ${p('reviseStageKey')} falló: ${truncate(p('error'), 120)}`
    case 'agent.status.changed':
    case 'stage.status.changed':
    case 'workflow.status.changed':
      return `${statusLabel(p('previousStatus'))} → ${statusLabel(p('status'))}`
    case 'workflow.definition.created':
      return `Borrador de ${p('key')} v${p('version')} creado`
    case 'workflow.definition.published':
      return `${p('key')} v${p('version')} publicado`
    case 'workflow.definition.discarded':
      return `Borrador de ${p('key')} v${p('version')} descartado`
    // El mismo tipo vale para un workflow (con `key`) y para una ejecución.
    case 'workflow.archived':
      return payload.key ? `Workflow ${p('key')} archivado` : 'Ejecución archivada'
    case 'workflow.restored':
      return payload.key ? `Workflow ${p('key')} restaurado` : 'Ejecución restaurada'
    default:
      return type
  }
}

function describeVerification(payload: Record<string, unknown>): string {
  if (payload.error) return `Verificación sin completar: ${String(payload.error)}`
  const tests = payload.tests as { total?: number; failed?: number; errors?: number } | undefined
  const summary = tests
    ? ` · ${tests.total ?? 0} tests, ${(tests.failed ?? 0) + (tests.errors ?? 0)} fallidos`
    : ''
  return `Verificación terminada (código ${String(payload.exitCode ?? '?')})${summary}`
}

/** Tamaño legible: «512 B», «3,4 KB», «12,1 MB». */
const QUOTA_WINDOW: Record<string, string> = { five_hour: 'de 5 h', seven_day: 'semanal' }

/** «Cuota de Codex: 23 % de 5 h, 13 % semanal». */
function describeQuota(payload: Record<string, unknown>): string {
  const windows = Array.isArray(payload.windows)
    ? (payload.windows as { window?: string; usedFraction?: number; label?: string }[])
    : []
  const parts = windows
    .filter((w) => typeof w.usedFraction === 'number' && w.window)
    .map(
      (w) =>
        `${w.label ? `${w.label} ` : ''}${Math.round(w.usedFraction! * 100)} % ${
          QUOTA_WINDOW[w.window!] ?? w.window
        }`,
    )
  return `Cuota de ${providerName(String(payload.provider ?? ''))}${
    parts.length > 0 ? `: ${parts.join(', ')}` : ''
  }`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ${units[unit]}`
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

/** Cifra corta para las tarjetas: 14.845 → «14,8 k», 2.300.000 → «2,3 M». */
export function formatCompact(n: number | null): string {
  if (n == null) return '—'
  const short = (v: number, unit: string) =>
    `${v.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ${unit}`
  if (Math.abs(n) >= 1_000_000) return short(n / 1_000_000, 'M')
  if (Math.abs(n) >= 10_000) return short(n / 1_000, 'k')
  return n.toLocaleString('es-ES')
}

const PROVIDER_NAMES: Record<string, string> = {
  'claude-code': 'Claude Code',
  codex: 'Codex',
  gemini: 'Gemini CLI',
  antigravity: 'Antigravity CLI',
}

/** Nombre para mostrar de un proveedor de agentes (el id si no se conoce). */
export function providerName(id: string): string {
  return PROVIDER_NAMES[id] ?? id
}

/** Proveedores de un runner: «Claude Code 2.1.288 · Codex». */
export function formatProviders(providers: { id: string; version: string | null }[]): string {
  return providers
    .map((p) => (p.version ? `${providerName(p.id)} ${p.version}` : providerName(p.id)))
    .join(' · ')
}
