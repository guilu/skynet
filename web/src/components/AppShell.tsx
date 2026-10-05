import type { ReactNode } from 'react'
import { NavLink } from 'react-router'
import { HealthIndicator } from '../HealthIndicator'
import { useTheme, type ThemeChoice } from '../theme'

const NAV = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/projects', label: 'Proyectos' },
  { to: '/workflows', label: 'Workflows' },
  { to: '/runs', label: 'Ejecuciones' },
  { to: '/runners', label: 'Runners' },
  { to: '/activity', label: 'Actividad' },
]

const THEMES: { value: ThemeChoice; label: string }[] = [
  { value: 'system', label: 'Sistema' },
  { value: 'light', label: 'Claro' },
  { value: 'dark', label: 'Oscuro' },
]

/** Estructura común: navegación global, estado del control plane, tema y contenido. */
export function AppShell({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useTheme()
  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Saltar al contenido
      </a>
      <header className="topbar">
        <span className="brand">Skynet</span>
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
      </header>
      <nav className="sidenav" aria-label="Navegación principal">
        <ul>
          {NAV.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} end={item.end}>
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
