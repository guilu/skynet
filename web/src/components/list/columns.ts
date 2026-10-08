import { useState } from 'react'

const key = (table: string) => `skynet.columns.${table}`

function readHidden(table: string, defaults: string[]): string[] {
  try {
    const raw = localStorage.getItem(key(table))
    if (raw) {
      const parsed: unknown = JSON.parse(raw)
      if (Array.isArray(parsed)) return parsed.filter((v): v is string => typeof v === 'string')
    }
  } catch {
    // Sin almacenamiento o con un valor roto: las columnas por defecto.
  }
  return defaults
}

/**
 * Columnas ocultas de una tabla, recordadas en este navegador. `defaults` son las que empiezan
 * ocultas la primera vez.
 */
export function useHiddenColumns(
  table: string,
  defaults: string[] = [],
): [Set<string>, (id: string, visible: boolean) => void] {
  const [hidden, setHidden] = useState(() => readHidden(table, defaults))
  const toggle = (id: string, visible: boolean) => {
    setHidden((prev) => {
      const next = visible ? prev.filter((c) => c !== id) : [...new Set([...prev, id])]
      try {
        localStorage.setItem(key(table), JSON.stringify(next))
      } catch {
        // La elección dura lo que la pestaña.
      }
      return next
    })
  }
  return [new Set(hidden), toggle]
}
