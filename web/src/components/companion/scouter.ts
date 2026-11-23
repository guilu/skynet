import { useSyncExternalStore } from 'react'

/** Cuánto se queda un texto en el scouter antes de volver al nombre del estado. */
export const SCOUTER_MS = 8_000

let text = ''
let timer: ReturnType<typeof setTimeout> | undefined
const listeners = new Set<() => void>()

function set(next: string) {
  text = next
  listeners.forEach((l) => l())
}

/**
 * Lo que dice Skyny (las frases al tocarlo, las reacciones a lo que haces) también sale en el
 * cristal del scouter del Skyny flotante, si lo lleva puesto. Pasado `ms`, vuelve al estado.
 */
export function sayOnScouter(next: string, ms: number = SCOUTER_MS) {
  clearTimeout(timer)
  set(next.trim())
  timer = setTimeout(() => set(''), ms)
}

/** Borra el texto del scouter ya (vuelve al nombre del estado). */
export function clearScouter() {
  clearTimeout(timer)
  set('')
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** El texto que tiene que enseñar el scouter ahora; vacío, el nombre del estado. */
export function useScouterText(): string {
  return useSyncExternalStore(subscribe, () => text)
}
