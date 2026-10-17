import type { ApprovalRisk, ApprovalStatus, ApprovalSummary } from '../../api'
import type { StatusTone } from '../../format'

export const RISK_LABEL: Record<ApprovalRisk, string> = {
  LOW: 'Riesgo bajo',
  MEDIUM: 'Riesgo medio',
  HIGH: 'Riesgo alto',
}

export const RISK_TONE: Record<ApprovalRisk, StatusTone> = {
  LOW: 'neutral',
  MEDIUM: 'warn',
  HIGH: 'bad',
}

export const APPROVAL_STATUS_LABEL: Record<ApprovalStatus, string> = {
  PENDING: 'Pendiente',
  APPROVED: 'Aprobada',
  REJECTED: 'Rechazada',
  CHANGES_REQUESTED: 'Cambios pedidos',
  CANCELLED: 'Cancelada',
  EXPIRED: 'Caducada',
}

export const APPROVAL_STATUS_TONE: Record<ApprovalStatus, StatusTone> = {
  PENDING: 'warn',
  APPROVED: 'ok',
  REJECTED: 'bad',
  CHANGES_REQUESTED: 'active',
  CANCELLED: 'neutral',
  EXPIRED: 'bad',
}

export const DECISION_LABEL: Record<string, string> = {
  APPROVE: 'Aprobó',
  REJECT: 'Rechazó',
  REQUEST_CHANGES: 'Pidió cambios',
  COMMENT: 'Comentó',
  CANCEL: 'Canceló',
  EXPIRE: 'Caducó',
}

/** «TKM-1 · Añadir precios», o lo que haya del trabajo. */
export function approvalWork(a: Pick<ApprovalSummary, 'origin'>): string {
  const w = a.origin.workItem
  return w.title ? `${w.key} · ${w.title}` : w.key
}

/** «aprobar-plan» o su nombre, con la ronda si no es la primera. */
export function approvalGate(a: Pick<ApprovalSummary, 'origin' | 'stageKey' | 'round'>): string {
  const name = a.origin.stage.name ?? a.stageKey
  return a.round > 1 ? `${name} · ronda ${a.round}` : name
}
