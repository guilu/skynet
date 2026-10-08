import { Search, X } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { cn } from '../../lib/cn'

/** Fila de controles encima de una lista: búsqueda, filtros y columnas. */
export function ListToolbar({ children }: { children: ReactNode }) {
  return <div className="list-toolbar">{children}</div>
}

/**
 * Búsqueda de una lista. Escribe en la URL tras una pausa breve, para no pedir una página por
 * cada tecla.
 */
export function SearchField({
  value,
  onChange,
  placeholder,
  label,
}: {
  value: string
  onChange: (value: string) => void
  placeholder: string
  label: string
}) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  useEffect(() => {
    if (text === value) return
    const id = setTimeout(() => onChange(text), 300)
    return () => clearTimeout(id)
    // onChange cambia en cada render de la página; basta con el texto.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text])
  return (
    <label className="search-field">
      <Search size={18} strokeWidth={2.5} aria-hidden="true" />
      <span className="visually-hidden">{label}</span>
      <input
        type="search"
        value={text}
        placeholder={placeholder}
        onChange={(e) => setText(e.target.value)}
      />
    </label>
  )
}

export interface ChipOption {
  value: string
  label: string
  /** Punto de color del estado, como en las píldoras. */
  tone?: 'active' | 'ok' | 'bad' | 'warn' | 'neutral'
}

/** Filtro por facetas: cada opción es un botón que se activa o desactiva (varias a la vez). */
export function FilterChips({
  label,
  options,
  selected,
  onChange,
}: {
  label: string
  options: ChipOption[]
  selected: string[]
  onChange: (selected: string[]) => void
}) {
  const toggle = (value: string) =>
    onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value])
  return (
    <div className="chips" role="group" aria-label={label}>
      {options.map((o) => {
        const on = selected.includes(o.value)
        return (
          <button
            key={o.value}
            type="button"
            className={cn('chip', on && 'chip-on')}
            aria-pressed={on}
            onClick={() => toggle(o.value)}
          >
            {o.tone && <span className={cn('chip-dot', `dot-${o.tone}`)} aria-hidden="true" />}
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

/** Filtro activo que no tiene chip propio (p. ej. «Creadas desde…»), con su botón para quitarlo. */
export function RemovableChip({
  children,
  onRemove,
  removeLabel,
}: {
  children: ReactNode
  onRemove: () => void
  removeLabel: string
}) {
  return (
    <span className="chip chip-on chip-removable">
      {children}
      <button type="button" onClick={onRemove} aria-label={removeLabel}>
        <X size={14} strokeWidth={3} aria-hidden="true" />
      </button>
    </span>
  )
}
