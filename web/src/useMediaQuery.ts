import { useSyncExternalStore } from 'react'

/** Si la ventana cumple la media query; sin `matchMedia` (jsdom), nunca. */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const media = window.matchMedia?.(query)
      media?.addEventListener('change', onChange)
      return () => media?.removeEventListener('change', onChange)
    },
    () => window.matchMedia?.(query).matches ?? false,
  )
}
