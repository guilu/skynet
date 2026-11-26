import { Suspense, useState, type ReactNode } from 'react'
import { Skyny3D } from './load3d'
import { can3D } from './look'
import { useSettledState } from './settle'
import type { CompanionState } from './state'
import { SkynySlot } from './SkynySlot'
import type { PulseType } from './pulse'
import type { LiveInfo, StageMove } from './three/Skyny3D'

/** Por debajo de este tamaño, letreros cortos («IDEA» en vez de «PENSANDO...»). */
const COMPACT_BELOW = 160

/**
 * Skyny en vivo (SV-B): el Skyny 3D que cuenta lo que pasa, con letreros, contador y una
 * permanencia mínima en cada estado (`useSettledState`). Mientras carga three deja su hueco
 * vacío; sin WebGL, con movimiento reducido o si el 3D falla, enseña `fallback` (el Skyny 2D).
 */
export function SkynyLive({
  state,
  size,
  fallback,
  interactive = false,
  pulse,
  stage,
  ...live
}: LiveInfo & {
  state: CompanionState
  size: number
  fallback: ReactNode
  /** Se puede tocar (¡boop!) y girar arrastrando, como en el login. */
  interactive?: boolean
  /** Reacción a un evento en vivo (`usePulse`). */
  pulse?: { type: PulseType; key: number } | null
  /** Entra o sale de escena volando (la cortina del chat). */
  stage?: StageMove | null
}) {
  const [three, setThree] = useState(can3D)
  const shown = useSettledState(state)
  if (!three) return fallback
  return (
    <Suspense fallback={<SkynySlot size={size} />}>
      <Skyny3D
        state={shown}
        size={size}
        live={{ compact: size < COMPACT_BELOW, ...live }}
        interactive={interactive}
        pulse={pulse}
        stage={stage}
        onFail={() => setThree(false)}
      />
    </Suspense>
  )
}
