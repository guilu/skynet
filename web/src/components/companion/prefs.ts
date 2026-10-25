import { useSyncExternalStore } from 'react'

/** Preferencias del companion en este navegador. */
export interface CompanionPrefs {
  /** La mascota en la barra superior. */
  mascot: boolean
  /** Los avisos flotantes; la campana guarda los avisos igualmente. */
  toasts: boolean
  /** Hasta cuándo (ms) no salen avisos flotantes; null si no está silenciado. */
  mutedUntil: number | null
}

const KEY = 'skynet.companion'
export const DEFAULT_PREFS: CompanionPrefs = { mascot: true, toasts: true, mutedUntil: null }
/** «Silenciar 1 h». */
export const MUTE_MS = 60 * 60 * 1000

const listeners = new Set<() => void>()
let cached: CompanionPrefs | null = null

function read(): CompanionPrefs {
  if (cached) return cached
  try {
    const raw = localStorage.getItem(KEY)
    cached = { ...DEFAULT_PREFS, ...(raw ? (JSON.parse(raw) as Partial<CompanionPrefs>) : {}) }
  } catch {
    cached = DEFAULT_PREFS
  }
  return cached
}

export function setCompanionPrefs(patch: Partial<CompanionPrefs>) {
  cached = { ...read(), ...patch }
  try {
    localStorage.setItem(KEY, JSON.stringify(cached))
  } catch {
    // Sin almacenamiento: la preferencia dura lo que la pestaña.
  }
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useCompanionPrefs(): CompanionPrefs {
  return useSyncExternalStore(subscribe, read, () => DEFAULT_PREFS)
}

/** Solo para las pruebas: vuelve a leer del almacenamiento. */
export function resetCompanionPrefsCache() {
  cached = null
}
