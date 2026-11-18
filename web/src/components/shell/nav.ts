import type { IconName } from '../icons/registry'
import type { ThemeChoice } from '../../theme'

export interface NavItem {
  to: string
  label: string
  icon: IconName
  end?: boolean
  /** Lleva el contador de aprobaciones pendientes. */
  pendingApprovals?: boolean
  /**
   * Páginas que se ven como pestañas de esta sección. La entrada de la barra lleva a la primera y
   * queda marcada en cualquiera de ellas.
   */
  tabs?: NavItem[]
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
      { to: '/work', label: 'Trabajos', icon: 'work-items' },
      { to: '/activity', label: 'Actividad', icon: 'activity' },
    ],
  },
  {
    id: 'factory',
    label: 'Fábrica',
    items: [
      { to: '/runs', label: 'En curso', icon: 'runs' },
      { to: '/workflows', label: 'Workflows', icon: 'workflows' },
      {
        to: '/approvals',
        label: 'Memoria',
        icon: 'memory',
        pendingApprovals: true,
        tabs: [
          { to: '/approvals', label: 'Aprobaciones', icon: 'approvals', pendingApprovals: true },
          { to: '/results', label: 'Resultados', icon: 'branch' },
          { to: '/judges', label: 'Jueces', icon: 'verification' },
          { to: '/lessons', label: 'Lecciones', icon: 'memory' },
          { to: '/evals', label: 'Evaluaciones', icon: 'circle-ok' },
        ],
      },
    ],
  },
  {
    id: 'admin',
    label: 'Administración',
    items: [
      {
        to: '/runners',
        label: 'Agentes',
        icon: 'runners',
        tabs: [
          { to: '/runners', label: 'Runners', icon: 'runners' },
          { to: '/providers', label: 'Proveedores', icon: 'provider' },
          { to: '/usage', label: 'Consumo', icon: 'usage' },
        ],
      },
      { to: '/settings', label: 'Ajustes', icon: 'settings' },
      { to: '/maintenance', label: 'Mantenimiento', icon: 'tool' },
    ],
  },
]

/** Todas las páginas, en el orden de la barra lateral, con su área y, si es pestaña, su sección. */
export const NAV: (NavItem & { area: string; group?: NavItem })[] = NAV_AREAS.flatMap((a) =>
  a.items.flatMap((i) =>
    i.tabs ? i.tabs.map((t) => ({ ...t, area: a.label, group: i })) : [{ ...i, area: a.label }],
  ),
)

const under = (pathname: string, to: string, end?: boolean) =>
  end ? pathname === to : pathname === to || pathname.startsWith(`${to}/`)

/** Si la ruta es de la entrada de la barra (o de una de sus pestañas). */
export function isActive(item: NavItem, pathname: string) {
  return (item.tabs ?? [item]).some((t) => under(pathname, t.to, t.end))
}

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
  return NAV.find((n) => under(pathname, n.to, n.end))
}

export const THEMES: { value: ThemeChoice; label: string; icon: IconName }[] = [
  { value: 'system', label: 'Sistema', icon: 'theme-system' },
  { value: 'light', label: 'Claro', icon: 'theme-light' },
  { value: 'dark', label: 'Oscuro', icon: 'theme-dark' },
]
