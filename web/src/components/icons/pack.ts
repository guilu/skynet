import { createContext, useContext, useSyncExternalStore } from 'react'

export type IconPack = 'lucide' | 'ilustrados' | 'chatgpt'

export const ICON_PACKS: { id: IconPack; label: string; hint: string }[] = [
  { id: 'lucide', label: 'Lucide', hint: 'De trazo fino y neutro. Los de siempre.' },
  {
    id: 'ilustrados',
    label: 'Ilustrados',
    hint: 'Formas lisas y de colores, como la mascota, con un gesto propio al pasar el ratón.',
  },
  {
    id: 'chatgpt',
    label: 'ChatGPT',
    hint: 'Con volumen y colores propios de cada objeto, como las láminas que diseñó ChatGPT.',
  },
]

export const DEFAULT_PACK: IconPack = 'lucide'

export const isIconPack = (value: unknown): value is IconPack =>
  ICON_PACKS.some((p) => p.id === value)

let current: IconPack = DEFAULT_PACK
const listeners = new Set<() => void>()

/**
 * Cambia el paquete de toda la web. Lo deja también en `data-icons` de `<html>`, por si una hoja
 * de estilos necesita saberlo.
 */
export function setIconPack(pack: IconPack) {
  if (pack === current) return
  current = pack
  document.documentElement.setAttribute('data-icons', pack)
  listeners.forEach((l) => l())
}

const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Fuerza un paquete en una parte de la página (las vistas previas de Ajustes). */
export const IconPackContext = createContext<IconPack | null>(null)

/** El paquete con el que se pinta aquí: el forzado por `IconPackContext` o el de la web. */
export function useIconPack(): IconPack {
  const global = useSyncExternalStore(subscribe, () => current)
  return useContext(IconPackContext) ?? global
}
