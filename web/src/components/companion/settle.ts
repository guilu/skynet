// Skyny en vivo (SV-B): los estados de una ejecución cambian deprisa (Pensando ↔ Ejecutando en
// cada herramienta), y Skyny no puede hacer un gesto por cada uno. Cada estado se queda un mínimo
// en pantalla; los que llegan mientras, se juntan en el último. Lo urgente (te necesita, falla,
// se corta) entra enseguida.
import { useEffect, useState } from 'react'
import type { CompanionState } from './state'

/** Lo mínimo que se queda Skyny en un estado antes de pasar al siguiente. */
export const DWELL_MS = 1200

/** Estados que no esperan: los que piden tu atención o cierran la ejecución. */
const URGENT: CompanionState[] = ['waiting', 'error', 'offline', 'cancelled']

/**
 * Qué enseñar ahora: `shown` (desde `since`) o `target`. Devuelve el estado y, si hay que
 * esperar para cambiar, cuántos ms faltan.
 */
export function settle(
  shown: CompanionState,
  since: number,
  target: CompanionState,
  now: number,
  dwell = DWELL_MS,
): { state: CompanionState; wait: number } {
  if (target === shown) return { state: shown, wait: 0 }
  const left = since + dwell - now
  if (left <= 0 || URGENT.includes(target)) return { state: target, wait: 0 }
  return { state: shown, wait: left }
}

/** El estado que enseña Skyny para `target`, con la permanencia mínima de `settle`. */
export function useSettledState(target: CompanionState, dwell = DWELL_MS): CompanionState {
  const [shown, setShown] = useState({ state: target, since: 0 })
  useEffect(() => {
    const step = () => {
      const now = performance.now()
      const next = settle(shown.state, shown.since, target, now, dwell)
      if (next.state !== shown.state) setShown({ state: next.state, since: now })
      return next.wait
    }
    const wait = step()
    if (wait <= 0) return
    const timer = setTimeout(step, wait)
    return () => clearTimeout(timer)
  }, [target, shown, dwell])
  return shown.state
}
