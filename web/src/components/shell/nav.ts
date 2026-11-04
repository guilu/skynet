import type { IconName } from '../icons/registry'
import type { ThemeChoice } from '../../theme'

export interface NavItem {
  to: string
  label: string
  icon: IconName
  end?: boolean
  /** Lleva el contador de aprobaciones pendientes. */
  pendingApprovals?: boolean
}

export interface NavArea {
  id: 'workspace' | 'factory' | 'admin'
  label: string
  items: NavItem[]
}

/**
 * Secciones de la web agrupadas en las tres áreas de la fábrica (FS-A): el espacio de trabajo
 * (sobre qué software se trabaja), la fábrica (qué hacen los agentes) y la administración (con qué
 * medios y a qué coste). Las rutas no cambian; solo la agrupación y algunos nombres.
 */
export const NAV_AREAS: NavArea[] = [
  {
    id: 'workspace',
    label: 'Espacio de trabajo',
    items: [
      { to: '/', label: 'Inicio', icon: 'dashboard', end: true },
      { to: '/projects', label: 'Proyectos', icon: 'projects' },
      { to: '/activity', label: 'Actividad', icon: 'activity' },
    ],
  },
  {
    id: 'factory',
    label: 'Fábrica',
    items: [
      { to: '/runs', label: 'Ejecuciones', icon: 'runs' },
      { to: '/workflows', label: 'Workflows', icon: 'workflows' },
      { to: '/approvals', label: 'Aprobaciones', icon: 'approvals', pendingApprovals: true },
    ],
  },
  {
    id: 'admin',
    label: 'Administración',
    items: [
      { to: '/runners', label: 'Runners', icon: 'runners' },
      { to: '/usage', label: 'Consumo', icon: 'usage' },
      { to: '/settings', label: 'Ajustes', icon: 'settings' },
    ],
  },
]

/** Todas las secciones, en el orden de la barra lateral, con su área. */
export const NAV: (NavItem & { area: string })[] = NAV_AREAS.flatMap((a) =>
  a.items.map((i) => ({ ...i, area: a.label })),
)

/**
 * Barra inferior del móvil: lo que se mira y se decide desde el teléfono. «Fábrica» lleva a las
 * ejecuciones y «Aprobar» a las aprobaciones; el resto está en «Más», agrupado por áreas.
 */
export const MOBILE_NAV: NavItem[] = [
  { to: '/', label: 'Inicio', icon: 'dashboard', end: true },
  { to: '/projects', label: 'Proyectos', icon: 'projects' },
  { to: '/runs', label: 'Fábrica', icon: 'runs' },
  { to: '/approvals', label: 'Aprobar', icon: 'approvals', pendingApprovals: true },
]

/** La sección (y su área) de una ruta. */
export function sectionOf(pathname: string) {
  return NAV.find((n) => (n.end ? pathname === n.to : pathname.startsWith(n.to)))
}

export const THEMES: { value: ThemeChoice; label: string; icon: IconName }[] = [
  { value: 'system', label: 'Sistema', icon: 'theme-system' },
  { value: 'light', label: 'Claro', icon: 'theme-light' },
  { value: 'dark', label: 'Oscuro', icon: 'theme-dark' },
]
