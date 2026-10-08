import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  CirclePlay,
  Folder,
  House,
  Server,
  Sparkles,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { NavLink } from 'react-router'
import { api, SESSION_KEY, type Session } from '../api'
import { HealthIndicator } from '../HealthIndicator'
import { useTheme, type ThemeChoice } from '../theme'
import { Button } from './ui/Button'

const NAV: { to: string; label: string; icon: LucideIcon; end?: boolean }[] = [
  { to: '/', label: 'Dashboard', icon: House, end: true },
  { to: '/projects', label: 'Proyectos', icon: Folder },
  { to: '/workflows', label: 'Workflows', icon: Workflow },
  { to: '/runs', label: 'Ejecuciones', icon: CirclePlay },
  { to: '/runners', label: 'Runners', icon: Server },
  { to: '/activity', label: 'Actividad', icon: Activity },
]

const THEMES: { value: ThemeChoice; label: string }[] = [
  { value: 'system', label: 'Sistema' },
  { value: 'light', label: 'Claro' },
  { value: 'dark', label: 'Oscuro' },
]

/** Estructura común: navegación global, estado del control plane, tema y contenido. */
export function AppShell({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useTheme()
  const queryClient = useQueryClient()
  const session = useQuery<Session | null>({ queryKey: SESSION_KEY, enabled: false })
  const logout = useMutation({
    mutationFn: api.logout,
    onSettled: () => {
      queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== SESSION_KEY[0] })
      queryClient.setQueryData(SESSION_KEY, null)
    },
  })
  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Saltar al contenido
      </a>
      <header className="topbar">
        <span className="brand">
          <span className="brand-mark" aria-hidden="true">
            <Sparkles size={20} strokeWidth={2.5} />
          </span>
          Skynet
        </span>
        <HealthIndicator />
        <label className="theme-picker">
          <span className="visually-hidden">Tema</span>
          <select value={theme} onChange={(e) => setTheme(e.target.value as ThemeChoice)}>
            {THEMES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>
        {session.data && (
          <span className="session">
            <span className="muted small">{session.data.username}</span>{' '}
            <Button
              type="button"
              variant="link"
              onClick={() => logout.mutate()}
              disabled={logout.isPending}
            >
              Salir
            </Button>
          </span>
        )}
      </header>
      <nav className="sidenav" aria-label="Navegación principal">
        <ul>
          {NAV.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} end={item.end}>
                <item.icon size={24} strokeWidth={2.25} aria-hidden="true" />
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <main id="main" className="content" tabIndex={-1}>
        {children}
      </main>
    </div>
  )
}
