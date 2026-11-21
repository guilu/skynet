import type { StatusTone } from '../../format'
import type { DrawingTone } from '../icons/ilustrados'
import type { IconName } from '../icons/registry'

/** Icono por tono, cuando no hay uno propio del estado. */
export const TONE_ICON: Record<StatusTone, IconName> = {
  neutral: 'status-ready',
  active: 'status-running',
  ok: 'status-ok',
  bad: 'status-error',
  warn: 'status-warn',
}

/** Color de la insignia ilustrada para cada tono de las píldoras. */
export const TONE_BADGE: Record<StatusTone, DrawingTone> = {
  neutral: 'idle',
  active: 'live',
  ok: 'ok',
  bad: 'bad',
  warn: 'warn',
}

/** Icono propio de cada estado conocido. */
const STATUS_ICON: Record<string, IconName> = {
  PENDING: 'status-pending',
  QUEUED: 'status-pending',
  READY: 'status-ready',
  STARTING: 'status-starting',
  RUNNING: 'status-running',
  THINKING: 'status-thinking',
  EXECUTING: 'status-executing',
  WAITING_FOR_INPUT: 'status-waiting',
  WAITING_FOR_APPROVAL: 'status-waiting',
  UNRESPONSIVE: 'status-paused',
  STALE: 'status-offline',
  CANCELLED: 'status-cancelled',
  SKIPPED: 'status-skipped',
}

export const statusIcon = (status: string, tone: StatusTone): IconName =>
  STATUS_ICON[status] ?? TONE_ICON[tone]
