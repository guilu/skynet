import { Suspense, useState } from 'react'
import { Companion, type Pose } from './Companion'
import { Skyny3D } from './load3d'
import { can3D } from './look'
import { SkynySlot } from './SkynySlot'

/** Lo que mide el Skyny del login: grande, protagonista de la pantalla. */
const SIZE_3D = 240
const SIZE_2D = 180

/**
 * Skyny del login (TD-A): en 3D (three, en un trozo aparte). Mientras carga deja su hueco vacío y
 * aparece con un fundido; sin WebGL, con movimiento reducido o si el 3D falla, el Skyny 2D.
 */
export function LoginSkyny({ pose }: { pose?: Pose }) {
  const [three, setThree] = useState(can3D)
  const flat = <Companion state="calm" size={SIZE_2D} look pose={pose} />
  if (!three) return flat
  return (
    <Suspense fallback={<SkynySlot size={SIZE_3D} />}>
      <Skyny3D size={SIZE_3D} pose={pose} onFail={() => setThree(false)} />
    </Suspense>
  )
}
