import { lazy, Suspense, useState } from 'react'
import { Companion, type Pose } from './Companion'
import { can3D } from './look'

const Skyny3D = lazy(() => import('./three/Skyny3D'))

/** Lo que mide el Skyny 3D del login; el 2D de respaldo ocupa lo mismo que antes. */
const SIZE_3D = 152
const SIZE_2D = 128

/**
 * Skyny del login (TD-A): en 3D (three, en un trozo aparte que solo se descarga aquí) y, mientras
 * carga, sin WebGL, con movimiento reducido o si el 3D falla, el Skyny 2D de siempre.
 */
export function LoginSkyny({ pose }: { pose?: Pose }) {
  const [three, setThree] = useState(can3D)
  const flat = <Companion state="calm" size={SIZE_2D} look pose={pose} />
  if (!three) return flat
  return (
    <Suspense fallback={flat}>
      <Skyny3D size={SIZE_3D} pose={pose} onFail={() => setThree(false)} />
    </Suspense>
  )
}
