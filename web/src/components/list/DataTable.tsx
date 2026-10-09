import * as Menu from '@radix-ui/react-dropdown-menu'
import { Check, Columns3, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

export interface Column<T> {
  id: string
  header: string
  cell: (row: T) => ReactNode
  /** Números alineados a la derecha. */
  numeric?: boolean
  /** La primera columna (la que identifica la fila) no se puede ocultar. */
  hideable?: boolean
  /** Cabecera solo para lectores de pantalla (p. ej. la de acciones). */
  hiddenHeader?: boolean
}

/** Selección de filas con casillas, para las acciones en bloque. */
export interface RowSelection<T> {
  selected: Set<string>
  onChange: (selected: Set<string>) => void
  /** Nombre de la fila para su casilla («Seleccionar TKM-1»). */
  rowLabel: (row: T) => string
}

/**
 * Tabla de una lista: filas en bloques redondeados, columnas que se pueden ocultar (`hidden`) y un
 * estado vacío con icono, texto y, si hace falta, una acción. Con `selection`, cada fila lleva una
 * casilla y la cabecera otra para todas las visibles.
 */
export function DataTable<T>({
  label,
  columns,
  rows,
  rowKey,
  hidden,
  empty,
  rowClassName,
  selection,
}: {
  label: string
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  hidden?: Set<string>
  empty: ReactNode
  rowClassName?: (row: T) => string | undefined
  selection?: RowSelection<T>
}) {
  const visible = columns.filter((c) => !hidden?.has(c.id))
  if (rows.length === 0) return <>{empty}</>
  const keys = rows.map(rowKey)
  const allSelected = !!selection && keys.every((k) => selection.selected.has(k))
  const toggle = (key: string, on: boolean) => {
    if (!selection) return
    const next = new Set(selection.selected)
    if (on) next.add(key)
    else next.delete(key)
    selection.onChange(next)
  }
  return (
    <div className="table-scroll">
      <table className="table data-table" aria-label={label}>
        <thead>
          <tr>
            {selection && (
              <th scope="col" className="row-check">
                <input
                  type="checkbox"
                  aria-label="Seleccionar todas"
                  checked={allSelected}
                  onChange={(e) =>
                    selection.onChange(e.target.checked ? new Set(keys) : new Set<string>())
                  }
                />
              </th>
            )}
            {visible.map((c) => (
              <th key={c.id} scope="col" className={cn(c.numeric && 'num')}>
                {c.hiddenHeader ? <span className="visually-hidden">{c.header}</span> : c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)} className={rowClassName?.(row)}>
              {selection && (
                <td className="row-check">
                  <input
                    type="checkbox"
                    aria-label={selection.rowLabel(row)}
                    checked={selection.selected.has(rowKey(row))}
                    onChange={(e) => toggle(rowKey(row), e.target.checked)}
                  />
                </td>
              )}
              {visible.map((c) => (
                <td key={c.id} className={cn(c.numeric && 'num')}>
                  {c.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Menú «Columnas» para mostrar u ocultar las columnas que lo permiten. */
export function ColumnsMenu<T>({
  columns,
  hidden,
  onToggle,
}: {
  columns: Column<T>[]
  hidden: Set<string>
  onToggle: (id: string, visible: boolean) => void
}) {
  return (
    <Menu.Root modal={false}>
      <Menu.Trigger className="btn btn-secondary btn-sm">
        <Columns3 size={16} strokeWidth={2.5} aria-hidden="true" />
        Columnas
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu" align="end" sideOffset={8}>
          <Menu.Label className="menu-label">Columnas visibles</Menu.Label>
          {columns
            .filter((c) => c.hideable !== false)
            .map((c) => (
              <Menu.CheckboxItem
                key={c.id}
                className="menu-item"
                checked={!hidden.has(c.id)}
                onCheckedChange={(v) => onToggle(c.id, v === true)}
                onSelect={(e) => e.preventDefault()}
              >
                {c.header}
                <Menu.ItemIndicator className="menu-check">
                  <Check size={16} strokeWidth={3} aria-hidden="true" />
                </Menu.ItemIndicator>
              </Menu.CheckboxItem>
            ))}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}

/** Estado vacío: icono grande, una frase y, si se puede hacer algo, la acción. */
export function EmptyState({
  icon: Icon,
  title,
  children,
}: {
  icon: LucideIcon
  title: string
  children?: ReactNode
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon" aria-hidden="true">
        <Icon size={28} strokeWidth={2.25} />
      </span>
      <p className="empty-title">{title}</p>
      {children && <div className="empty-body">{children}</div>}
    </div>
  )
}
