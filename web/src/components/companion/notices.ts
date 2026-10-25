import type { Run, StoredEvent } from '../../api'
import { elapsed, formatCost, formatDuration, providerName } from '../../format'
import type { CompanionState } from './state'

/** Los tipos que escucha el companion global: estados, aprobaciones y límites (plan, §7). */
export const COMPANION_EVENT_TYPES = [
  'workflow.*',
  'run.resumed',
  'stage.status.changed',
  'stage.retry.scheduled',
  'stage.verification.*',
  'agent.status.changed',
  'agent.rate_limit',
  'approval.*',
]

export type NoticeKind =
  'approval' | 'input' | 'success' | 'error' | 'started' | 'unresponsive' | 'limit'

/** Un aviso de la campana: lo que pasó, en una ejecución y con un enlace para verlo. */
export interface Notice {
  id: string
  kind: NoticeKind
  /** La clave del trabajo («DEMO-9») o, si no tiene, el principio del id de la ejecución. */
  runKey: string
  runId: string | null
  approvalId: string | null
  title: string
  body: string
  href: string
  /** Texto del enlace del aviso flotante. */
  action: string
  at: number
  read: boolean
}

export const NOTICE_FACE: Record<NoticeKind, CompanionState> = {
  approval: 'waiting',
  input: 'waiting',
  success: 'success',
  error: 'error',
  started: 'running',
  unresponsive: 'offline',
  limit: 'paused',
}

/** Cuánto dura el aviso flotante; null: hasta que lo cierras (o decides, si es una aprobación). */
export const TOAST_MS: Record<NoticeKind, number | null> = {
  approval: null,
  input: null,
  success: 6_000,
  error: null,
  started: 4_000,
  unresponsive: null,
  limit: 6_000,
}

/** Avisos flotantes a la vez; los demás quedan en la campana. */
export const MAX_TOASTS = 3
/** Dos avisos del mismo tipo con menos de esto entre ellos van en la misma tarjeta. */
export const GROUP_MS = 10_000
/** Avisos que guarda la campana. */
export const HISTORY = 50

export const runKeyOf = (run: Pick<Run, 'id' | 'workItemKey'> | null | undefined, id: string) =>
  run?.workItemKey ?? id.slice(0, 8)

/**
 * El aviso de un evento, o null si no merece uno (los cambios internos, como pensando ↔
 * ejecutando, no avisan). `run` es la ejecución ya leída tras el evento, si la hay.
 */
export function noticeFor(
  event: StoredEvent,
  run: Run | null,
  now: number = Date.now(),
): Notice | null {
  const p = event.payload
  const runId = event.workflowRunId
  const runKey = runId ? runKeyOf(run, runId) : '—'
  const runHref = runId ? `/runs/${runId}` : '/runs'
  const base = {
    id: `${event.sequence}`,
    runKey,
    runId,
    approvalId: null,
    href: runHref,
    action: 'Ver',
    at: now,
    read: false,
  }
  switch (event.type) {
    case 'approval.requested': {
      const approvalId = String(p.approvalId ?? '')
      return {
        ...base,
        kind: 'approval',
        approvalId,
        title: 'Te necesito',
        body: `La fase «${String(p.stageKey ?? '')}» de ${runKey} espera tu decisión`,
        href: approvalId ? `/approvals/${approvalId}` : '/approvals',
        action: 'Revisar',
      }
    }
    case 'workflow.started':
    case 'run.resumed':
      return {
        ...base,
        kind: 'started',
        title: 'Ejecutando workflow',
        body: [runKey, run?.workflow?.name].filter(Boolean).join(' · '),
      }
    case 'workflow.status.changed': {
      if (p.status === 'SUCCEEDED') {
        const ms = run ? elapsed(run.startedAt, run.finishedAt, now) : null
        const cost = run?.totals.costUsd
        return {
          ...base,
          kind: 'success',
          title: 'Workflow completado',
          body: [runKey, ms != null && formatDuration(ms), cost != null && formatCost(cost)]
            .filter(Boolean)
            .join(' · '),
        }
      }
      if (p.status === 'FAILED') {
        const stage = run?.stages.find((s) => s.status === 'FAILED')
        return {
          ...base,
          kind: 'error',
          title: 'Ha ocurrido un error',
          body: stage ? `${runKey} · fase «${stage.name ?? stage.stageKey}»` : runKey,
        }
      }
      return null
    }
    case 'agent.status.changed':
      if (p.status === 'WAITING_FOR_INPUT')
        return { ...base, kind: 'input', title: `${runKey} te pregunta algo`, body: '' }
      if (p.status === 'UNRESPONSIVE')
        return {
          ...base,
          kind: 'unresponsive',
          title: `El agente de ${runKey} no responde`,
          body: '',
        }
      return null
    case 'agent.rate_limit': {
      if (p.status === 'allowed' || p.status === 'allowed_warning') return null
      const agent = run?.stages.flatMap((s) => s.agents).find((a) => a.id === event.aggregateId)
      const who = agent ? providerName(agent.provider) : 'Un agente'
      const resets = typeof p.resetsAt === 'number' ? p.resetsAt * 1000 : null
      return {
        ...base,
        kind: 'limit',
        title: `${who} está al límite`,
        body: resets
          ? `${runKey} · reintento a las ${new Date(resets).toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' })}`
          : runKey,
      }
    }
    default:
      return null
  }
}

/** Una tarjeta flotante: uno o varios avisos del mismo tipo seguidos. */
export interface Toast {
  id: string
  kind: NoticeKind
  notices: Notice[]
  /** Cuándo llegó el último aviso; el siguiente del mismo tipo se agrupa si está cerca. */
  at: number
}

/**
 * Añade un aviso a las tarjetas: si hay una del mismo tipo de hace menos de `GROUP_MS`, se suma a
 * ella y pasa arriba; si no, entra una nueva. Como mucho `MAX_TOASTS`: sale la más antigua.
 */
export function pushToast(toasts: Toast[], notice: Notice): Toast[] {
  const group = toasts.find((t) => t.kind === notice.kind && notice.at - t.at < GROUP_MS)
  if (group) {
    const merged = { ...group, notices: [...group.notices, notice], at: notice.at }
    return [merged, ...toasts.filter((t) => t !== group)]
  }
  const fresh: Toast = { id: notice.id, kind: notice.kind, notices: [notice], at: notice.at }
  return [fresh, ...toasts].slice(0, MAX_TOASTS)
}

const PLURAL: Record<NoticeKind, (n: number) => string> = {
  approval: (n) => `${n} aprobaciones te esperan`,
  input: (n) => `${n} agentes te preguntan algo`,
  success: (n) => `${n} ejecuciones completadas`,
  error: (n) => `${n} ejecuciones han fallado`,
  started: (n) => `${n} ejecuciones en marcha`,
  unresponsive: (n) => `${n} agentes no responden`,
  limit: (n) => `${n} agentes están al límite`,
}

const GROUP_HREF: Record<NoticeKind, string> = {
  approval: '/approvals',
  input: '/runs',
  success: '/runs',
  error: '/runs?status=FAILED',
  started: '/runs?status=RUNNING',
  unresponsive: '/runs',
  limit: '/usage',
}

/** «DEMO-9, DEMO-11 y DEMO-12», sin repetir. */
export function listKeys(keys: string[]): string {
  const unique = [...new Set(keys)]
  if (unique.length <= 1) return unique.join('')
  return `${unique.slice(0, -1).join(', ')} y ${unique.at(-1)}`
}

/** Lo que pone una tarjeta: el aviso tal cual, o el resumen si agrupa varios. */
export function toastText(toast: Toast): Pick<Notice, 'title' | 'body' | 'href' | 'action'> {
  const [first] = toast.notices
  if (toast.notices.length === 1) return first
  return {
    title: PLURAL[toast.kind](toast.notices.length),
    body: listKeys(toast.notices.map((n) => n.runKey)),
    href: GROUP_HREF[toast.kind],
    action: 'Ver todas',
  }
}

/** «2 ejecuciones activas · 1 aprobación pendiente», para la mascota de la barra. */
export function summarize(runs: number, approvals: number, offline: boolean): string {
  if (offline) return 'Sin conexión en vivo; reconectando…'
  const parts = [
    runs > 0 && `${runs} ${runs === 1 ? 'ejecución activa' : 'ejecuciones activas'}`,
    approvals > 0 &&
      `${approvals} ${approvals === 1 ? 'aprobación pendiente' : 'aprobaciones pendientes'}`,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : 'Todo al día'
}
