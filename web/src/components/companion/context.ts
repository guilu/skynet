import { createContext, useContext } from 'react'
import type { Run } from '../../api'
import type { StreamState } from '../../useEventStream'
import type { Notice, Toast } from './notices'
import type { CompanionState } from './state'

export interface CompanionContextValue {
  expression: CompanionState
  /** «2 ejecuciones activas · 1 aprobación pendiente», para el botón y su menú. */
  summary: string
  activeRuns: Run[]
  pendingApprovals: number
  notices: Notice[]
  unread: number
  /** Marca los avisos como leídos (al abrir la campana): también da por visto el error. */
  markRead: () => void
  clearNotices: () => void
  toasts: Toast[]
  dismissToast: (id: string) => void
  streamState: StreamState
}

export const CompanionContext = createContext<CompanionContextValue | null>(null)

export function useCompanion(): CompanionContextValue {
  const value = useContext(CompanionContext)
  if (!value) throw new Error('useCompanion fuera de CompanionProvider')
  return value
}
