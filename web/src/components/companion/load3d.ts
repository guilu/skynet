import { lazy } from 'react'
import { can3D } from './look'

/** El trozo con three y el Skyny 3D; se descarga una sola vez, la primera vez que se pide. */
const load = () => import('./three/Skyny3D')

/** Skyny 3D perezoso: el mismo trozo para el login, la cortina del chat y la banda En curso. */
export const Skyny3D = lazy(load)

/**
 * Descarga three en un rato libre del navegador, para que el primer Skyny 3D (la cortina del chat,
 * En curso) salga ya en 3D sin pasar por un hueco. No hace nada si no habrá 3D.
 */
export function preloadSkyny3D() {
  if (!can3D()) return
  const idle = window.requestIdleCallback ?? ((cb: () => void) => window.setTimeout(cb, 1500))
  idle(() => void load().catch(() => {}))
}
