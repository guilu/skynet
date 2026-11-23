import * as Tooltip from '@radix-ui/react-tooltip'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { api, SESSION_KEY, type Session } from '../api'
import { useTheme } from '../theme'
import { CompanionMenu } from './companion/CompanionMenu'
import { CompanionProvider } from './companion/CompanionProvider'
import { FloatingSkyny } from './companion/FloatingSkyny'
import { preloadSkyny3D } from './companion/load3d'
import { NoticeBell } from './companion/NoticeBell'
import { SkynyPaused } from './companion/pause'
import { useCompanionPrefs } from './companion/prefs'
import { Toasts } from './companion/Toasts'
import { Breadcrumbs } from './shell/Breadcrumbs'
import { ThemeMenu, UserMenu } from './shell/Menus'
import { MobileNav } from './shell/MobileNav'
import { SectionTabs } from './shell/SectionTabs'
import { Sidebar } from './shell/Sidebar'
import { Icon } from './icons/Icon'

// La paleta (cmdk) se descarga la primera vez que se abre.
const CommandPalette = lazy(() =>
  import('./shell/CommandPalette').then((m) => ({ default: m.CommandPalette })),
)

// La cortina del chat con Skyny (CH-D) también se descarga la primera vez que se abre.
const ChatCurtain = lazy(() =>
  import('./chat/ChatCurtain').then((m) => ({ default: m.ChatCurtain })),
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
 * Estructura común: barra superior a todo lo ancho (la marca, que abre el chat con Skyny ⌘J, migas,
 * búsqueda ⌘K, companion, avisos, tema y cuenta) y, debajo, la barra lateral (plegable) con las
 * secciones y el contenido. En el móvil la barra lateral pasa a una barra
 * inferior con un cajón para el resto.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  const [theme, setTheme] = useTheme()
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const [paletteOpen, setPaletteOpen] = useState(false)
  // En el móvil, las cuatro acciones de la barra van plegadas tras «…» (se pliegan al cambiar de página).
  const [toolsOpen, setToolsOpen] = useState(false)
  const [toolsPath, setToolsPath] = useState(pathname)
  if (toolsPath !== pathname) {
    setToolsPath(pathname)
    setToolsOpen(false)
  }
  const [chatOpen, setChatOpen] = useState(false)
  // Montada desde la primera vez que se abre: al cerrarla conserva el borrador y la conversación.
  const [chatLoaded, setChatLoaded] = useState(false)
  const [noticesOpen, setNoticesOpen] = useState(false)
  const companion = useCompanionPrefs()
  const queryClient = useQueryClient()
  const session = useQuery<Session | null>({ queryKey: SESSION_KEY, enabled: false })
  const logout = useMutation({
    mutationFn: api.logout,
    onSettled: () => {
      queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== SESSION_KEY[0] })
      queryClient.setQueryData(SESSION_KEY, null)
    },
  })

  // three en un rato libre: la cortina del chat y En curso sacan a Skyny ya en 3D.
  useEffect(preloadSkyny3D, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((o) => !o)
      }
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'j') {
        e.preventDefault()
        setChatLoaded(true)
        setChatOpen((o) => !o)
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
      <CompanionProvider>
        <div className="shell" data-collapsed={collapsed || undefined}>
          <a className="skip-link" href="#main">
            Saltar al contenido
          </a>
          <Sidebar collapsed={collapsed} onToggle={toggleCollapsed} />
          <header className="topbar">
            <Tooltip.Root>
              <Tooltip.Trigger asChild>
                <button
                  type="button"
                  className="brand"
                  onClick={() => {
                    setChatLoaded(true)
                    setChatOpen(true)
                  }}
                  aria-label="Pregúntale a Skyny"
                  aria-expanded={chatOpen}
                  aria-keyshortcuts="Meta+J Control+J"
                >
                  <span className="brand-name">SKYNET</span>
                </button>
              </Tooltip.Trigger>
              <Tooltip.Portal>
                <Tooltip.Content className="tooltip" side="bottom" align="start" sideOffset={8}>
                  Pregúntale a Skyny <kbd className="kbd">⌘J</kbd>
                </Tooltip.Content>
              </Tooltip.Portal>
            </Tooltip.Root>
            <Breadcrumbs />
            <div className="topbar-more-wrap">
              <div
                id="topbar-tools"
                className="topbar-tools"
                data-open={toolsOpen || undefined}
                onKeyDown={(e) => {
                  if (e.key === 'Escape') setToolsOpen(false)
                }}
              >
                <button
                  type="button"
                  className="search-btn"
                  onClick={() => setPaletteOpen(true)}
                  aria-label="Buscar o ejecutar una orden"
                  aria-keyshortcuts="Meta+K Control+K"
                >
                  <Icon name="search" size={20} strokeWidth={2.5} />
                  <span className="search-label">Buscar ejecución, trabajo o runner…</span>
                  <kbd className="kbd">⌘K</kbd>
                </button>
                {companion.mascot && <CompanionMenu onOpenNotices={() => setNoticesOpen(true)} />}
                <NoticeBell open={noticesOpen} onOpenChange={setNoticesOpen} />
                <ThemeMenu theme={theme} onChange={setTheme} />
              </div>
              <button
                type="button"
                className="icon-btn topbar-more"
                aria-label={
                  toolsOpen
                    ? 'Ocultar las acciones de la barra'
                    : 'Mostrar las acciones de la barra'
                }
                aria-expanded={toolsOpen}
                aria-controls="topbar-tools"
                onClick={() => setToolsOpen((open) => !open)}
              >
                <Icon name={toolsOpen ? 'close' : 'more'} size={22} strokeWidth={2.25} />
              </button>
            </div>
            {session.data && (
              <UserMenu
                username={session.data.username}
                onLogout={() => logout.mutate()}
                pending={logout.isPending}
              />
            )}
          </header>
          <main id="main" className="content" tabIndex={-1}>
            {/* Con la clave, cada página nueva entra con una animación corta (UI-F). */}
            <SectionTabs />
            <div key={pathname} className="page">
              <SkynyPaused.Provider value={chatOpen}>{children}</SkynyPaused.Provider>
            </div>
          </main>
          <MobileNav />
          {/* Skyny 3D en su capa, por encima de la página; se va volando al abrir la cortina. */}
          <FloatingSkyny away={chatOpen} />
          {paletteOpen && (
            <Suspense fallback={null}>
              <CommandPalette open onOpenChange={setPaletteOpen} onTheme={setTheme} />
            </Suspense>
          )}
          {chatLoaded && (
            <Suspense fallback={null}>
              <ChatCurtain open={chatOpen} onOpenChange={setChatOpen} />
            </Suspense>
          )}
          <Toasts />
        </div>
      </CompanionProvider>
    </Tooltip.Provider>
  )
}
