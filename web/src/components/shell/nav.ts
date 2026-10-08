import {
  Activity,
  CirclePlay,
  Folder,
  House,
  Monitor,
  Moon,
  Server,
  Sun,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import type { ThemeChoice } from '../../theme'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  end?: boolean
  /** Aparece en la barra inferior del móvil; el resto va en «Más». */
  mobile?: boolean
}

/** Secciones de la web, en el orden de la barra lateral. */
export const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: House, end: true, mobile: true },
  { to: '/projects', label: 'Proyectos', icon: Folder, mobile: true },
  { to: '/workflows', label: 'Workflows', icon: Workflow },
  { to: '/runs', label: 'Ejecuciones', icon: CirclePlay, mobile: true },
  { to: '/runners', label: 'Runners', icon: Server, mobile: true },
  { to: '/activity', label: 'Actividad', icon: Activity },
]

export const THEMES: { value: ThemeChoice; label: string; icon: LucideIcon }[] = [
  { value: 'system', label: 'Sistema', icon: Monitor },
  { value: 'light', label: 'Claro', icon: Sun },
  { value: 'dark', label: 'Oscuro', icon: Moon },
]
