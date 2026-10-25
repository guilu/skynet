import type { IconName } from '../icons/registry'
import type { ThemeChoice } from '../../theme'

export interface NavItem {
  to: string
  label: string
  icon: IconName
  end?: boolean
  /** Aparece en la barra inferior del móvil; el resto va en «Más». */
  mobile?: boolean
  /** Lleva el contador de aprobaciones pendientes. */
  pendingApprovals?: boolean
}

/** Secciones de la web, en el orden de la barra lateral. */
export const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: 'dashboard', end: true, mobile: true },
  { to: '/projects', label: 'Proyectos', icon: 'projects', mobile: true },
  { to: '/workflows', label: 'Workflows', icon: 'workflows' },
  { to: '/runs', label: 'Ejecuciones', icon: 'runs', mobile: true },
  { to: '/approvals', label: 'Aprobaciones', icon: 'approvals', pendingApprovals: true },
  { to: '/runners', label: 'Runners', icon: 'runners' },
  { to: '/usage', label: 'Uso', icon: 'usage', mobile: true },
  { to: '/activity', label: 'Actividad', icon: 'activity' },
  { to: '/settings', label: 'Ajustes', icon: 'settings' },
]

export const THEMES: { value: ThemeChoice; label: string; icon: IconName }[] = [
  { value: 'system', label: 'Sistema', icon: 'theme-system' },
  { value: 'light', label: 'Claro', icon: 'theme-light' },
  { value: 'dark', label: 'Oscuro', icon: 'theme-dark' },
]
