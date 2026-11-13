import { lazy, Suspense, useState, type ReactNode } from 'react'
import { can3D } from './look'
import { useSettledState } from './settle'
import type { CompanionState } from './state'
import type { LiveInfo } from './three/Skyny3D'

const Skyny3D = lazy(() => import('./three/Skyny3D'))

/** Por debajo de este tamaño, letreros cortos («IDEA» en vez de «PENSANDO...»). */
const COMPACT_BELOW = 160

/**
 * Skyny en vivo (SV-B): el Skyny 3D que cuenta lo que pasa, con letreros, contador y una
 * permanencia mínima en cada estado (`useSettledState`). Mientras carga three, sin WebGL, con
 * movimiento reducido o si el 3D falla, enseña `fallback` (el Skyny 2D de siempre).
 */
export function SkynyLive({
  state,
  size,
  fallback,
  ...live
}: LiveInfo & { state: CompanionState; size: number; fallback: ReactNode }) {
  const [three, setThree] = useState(can3D)
  const shown = useSettledState(state)
  if (!three) return fallback
  return (
    <Suspense fallback={fallback}>
      <Skyny3D
        state={shown}
        size={size}
        live={{ compact: size < COMPACT_BELOW, ...live }}
        onFail={() => setThree(false)}
      />
    </Suspense>
  )
}
