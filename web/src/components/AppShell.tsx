import * as Tooltip from '@radix-ui/react-tooltip'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react'
import { api, SESSION_KEY, type Session } from '../api'
import { useTheme } from '../theme'
import { Breadcrumbs } from './shell/Breadcrumbs'
import { ThemeMenu, UserMenu } from './shell/Menus'
import { MobileNav } from './shell/MobileNav'
import { Sidebar } from './shell/Sidebar'

// La paleta (cmdk) se descarga la primera vez que se abre.
const CommandPalette = lazy(() =>
  import('./shell/CommandPalette').then((m) => ({ default: m.CommandPalette })),
)

const COLLAPSED_KEY = 'skynet.sidebar.collapsed'

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

/**
 * Estructura común: barra lateral (plegable) con las secciones, barra superior con migas,
 * búsqueda ⌘K, tema y cuenta, y el contenido. En el móvil la barra lateral pasa a una barra
 * inferior con un cajón para el resto.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useTheme()
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const queryClient = useQueryClient()
  const session = useQuery<Session | null>({ queryKey: SESSION_KEY, enabled: false })
  const logout = useMutation({
    mutationFn: api.logout,
    onSettled: () => {
      queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== SESSION_KEY[0] })
      queryClient.setQueryData(SESSION_KEY, null)
    },
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((o) => !o)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const toggleCollapsed = () => {
    setCollapsed((c) => {
      try {
        localStorage.setItem(COLLAPSED_KEY, c ? '0' : '1')
      } catch {
        // Sin almacenamiento: la preferencia dura lo que la pestaña.
      }
      return !c
    })
  }

  return (
    <Tooltip.Provider delayDuration={200}>
      <div className="shell" data-collapsed={collapsed || undefined}>
        <a className="skip-link" href="#main">
          Saltar al contenido
        </a>
        <Sidebar collapsed={collapsed} onToggle={toggleCollapsed} />
        <header className="topbar">
          <Breadcrumbs />
          <button
            type="button"
            className="search-btn"
            onClick={() => setPaletteOpen(true)}
            aria-label="Buscar o ejecutar una orden"
            aria-keyshortcuts="Meta+K Control+K"
          >
            <Search size={20} strokeWidth={2.5} aria-hidden="true" />
            <span className="search-label">Buscar ejecución, trabajo o runner…</span>
            <kbd className="kbd">⌘K</kbd>
          </button>
          <ThemeMenu theme={theme} onChange={setTheme} />
          {session.data && (
            <UserMenu
              username={session.data.username}
              onLogout={() => logout.mutate()}
              pending={logout.isPending}
            />
          )}
        </header>
        <main id="main" className="content" tabIndex={-1}>
          {children}
        </main>
        <MobileNav />
        {paletteOpen && (
          <Suspense fallback={null}>
            <CommandPalette open onOpenChange={setPaletteOpen} onTheme={setTheme} />
          </Suspense>
        )}
      </div>
    </Tooltip.Provider>
  )
}
