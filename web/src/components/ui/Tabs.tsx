import type { LucideIcon } from 'lucide-react'
import { useRef, type KeyboardEvent, type ReactNode } from 'react'

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
 */
export function TabList<T extends string>({
  label,
  tabs,
  selected,
  onSelect,
  idPrefix,
  panelId,
}: {
  label: string
  tabs: readonly TabItem<T>[]
  selected: T
  onSelect: (id: T) => void
  idPrefix: string
  panelId: string
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const current = tabs.findIndex((t) => t.id === selected)

  function onKeyDown(e: KeyboardEvent) {
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
    refs.current[target]?.focus()
  }

  return (
    <div role="tablist" aria-label={label} className="tabs" onKeyDown={onKeyDown}>
      {tabs.map((t, i) => {
        const Icon = t.icon
        return (
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
            {Icon && <Icon size={16} aria-hidden="true" />}
            {t.label}
            {t.count != null && <span className="tab-count">{t.count}</span>}
          </button>
        )
      })}
    </div>
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
