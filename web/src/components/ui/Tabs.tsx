import * as Menu from '@radix-ui/react-dropdown-menu'
import { Check, ChevronDown, type LucideIcon } from 'lucide-react'
import { useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { fitTabs } from './tabsFit'

export interface TabItem<T extends string> {
  id: T
  label: string
  icon?: LucideIcon
  /** Número junto a la etiqueta, p. ej. los eventos que hay. */
  count?: number
}

/**
 * Lista de pestañas con el patrón de WAI-ARIA: las flechas, Inicio y Fin mueven el foco y activan
 * la pestaña. Cada pestaña controla el panel `panelId`, que pinta quien la usa con `TabPanel`.
 * Con `overflowMenu`, las que no caben van a un menú «Más» en vez de hacer scroll.
 */
export function TabList<T extends string>({
  label,
  tabs,
  selected,
  onSelect,
  idPrefix,
  panelId,
  overflowMenu = false,
}: {
  label: string
  tabs: readonly TabItem<T>[]
  selected: T
  onSelect: (id: T) => void
  idPrefix: string
  panelId: string
  overflowMenu?: boolean
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const moreRef = useRef<HTMLButtonElement | null>(null)
  const measureRef = useRef<HTMLDivElement | null>(null)
  const current = tabs.findIndex((t) => t.id === selected)
  const [visible, setVisible] = useState(tabs.length)

  useLayoutEffect(() => {
    const box = measureRef.current
    if (!box) return
    const measure = () => {
      const row = box.firstElementChild as HTMLElement
      const w = [...row.children].map((el) => el.getBoundingClientRect().width)
      const n = tabs.length
      const gap = parseFloat(getComputedStyle(row).columnGap) || 0
      const sum = w.reduce((a, b) => a + b, 0)
      const widths = {
        tabs: w.slice(0, n),
        withChevron: w.slice(n, 2 * n),
        more: w[2 * n],
        gap,
        frame: row.getBoundingClientRect().width - sum - gap * (w.length - 1),
      }
      setVisible(fitTabs(widths, box.clientWidth, current))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(box)
    return () => observer.disconnect()
  }, [tabs, current])

  const shown = overflowMenu ? Math.min(visible, tabs.length) : tabs.length
  const hidden = tabs.slice(shown)
  const selectedHidden = current >= shown

  function onKeyDown(e: KeyboardEvent) {
    // El menú se pinta en un portal, pero sus teclas también llegan aquí.
    if (!e.currentTarget.contains(e.target as Node)) return
    const last = tabs.length - 1
    const target = {
      ArrowRight: current === last ? 0 : current + 1,
      ArrowLeft: current === 0 ? last : current - 1,
      Home: 0,
      End: last,
    }[e.key]
    if (target === undefined) return
    e.preventDefault()
    onSelect(tabs[target].id)
    ;(target < shown ? refs.current[target] : moreRef.current)?.focus()
  }

  const list = (
    <div role="tablist" aria-label={label} className="tabs" onKeyDown={onKeyDown}>
      {tabs.slice(0, shown).map((t, i) => (
        <button
          key={t.id}
          ref={(el) => {
            refs.current[i] = el
          }}
          type="button"
          role="tab"
          id={`${idPrefix}-${t.id}`}
          aria-selected={t.id === selected}
          aria-controls={panelId}
          tabIndex={t.id === selected ? 0 : -1}
          onClick={() => onSelect(t.id)}
        >
          <TabLabel tab={t} />
        </button>
      ))}
      {hidden.length > 0 && (
        <Menu.Root modal={false}>
          <Menu.Trigger asChild>
            {/* Si la elegida está en el menú, el botón hace de su pestaña y lleva su nombre. */}
            <button
              ref={moreRef}
              type="button"
              role="tab"
              id={selectedHidden ? `${idPrefix}-${selected}` : `${idPrefix}-more`}
              aria-selected={selectedHidden}
              aria-controls={panelId}
              tabIndex={selectedHidden ? 0 : -1}
            >
              {selectedHidden ? <TabLabel tab={tabs[current]} /> : 'Más'}
              <ChevronDown size={16} strokeWidth={2.5} aria-hidden="true" />
            </button>
          </Menu.Trigger>
          <Menu.Portal>
            <Menu.Content className="menu" align="end" sideOffset={8}>
              <Menu.RadioGroup value={selected} onValueChange={(v) => onSelect(v as T)}>
                {hidden.map((t) => (
                  <Menu.RadioItem key={t.id} value={t.id} className="menu-item">
                    <TabLabel tab={t} />
                    <Menu.ItemIndicator className="menu-check">
                      <Check size={16} strokeWidth={3} aria-hidden="true" />
                    </Menu.ItemIndicator>
                  </Menu.RadioItem>
                ))}
              </Menu.RadioGroup>
            </Menu.Content>
          </Menu.Portal>
        </Menu.Root>
      )}
    </div>
  )
  if (!overflowMenu) return list

  return (
    <>
      {/* Copia invisible con todas las pestañas: de aquí salen los anchos y el sitio que hay. */}
      <div ref={measureRef} className="tabs-measure" aria-hidden="true" inert>
        <div className="tabs">
          {tabs.map((t) => (
            <span key={t.id} role="tab">
              <TabLabel tab={t} />
            </span>
          ))}
          {tabs.map((t) => (
            <span key={t.id} role="tab">
              <TabLabel tab={t} />
              <ChevronDown size={16} strokeWidth={2.5} />
            </span>
          ))}
          <span role="tab">
            Más
            <ChevronDown size={16} strokeWidth={2.5} />
          </span>
        </div>
      </div>
      {list}
    </>
  )
}

function TabLabel<T extends string>({ tab }: { tab: TabItem<T> }) {
  const Icon = tab.icon
  return (
    <>
      {Icon && <Icon size={16} aria-hidden="true" />}
      {tab.label}
      {tab.count != null && <span className="tab-count">{tab.count}</span>}
    </>
  )
}

export function TabPanel({
  id,
  labelledBy,
  className,
  children,
}: {
  id: string
  labelledBy: string
  className?: string
  children: ReactNode
}) {
  return (
    <div role="tabpanel" id={id} aria-labelledby={labelledBy} tabIndex={0} className={className}>
      {children}
    </div>
  )
}
