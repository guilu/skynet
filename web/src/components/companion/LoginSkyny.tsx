import { Suspense, useEffect, useState } from 'react'
import { Companion, type Pose } from './Companion'
import { Skyny3D } from './load3d'
import { COVER_AFTER_MS, FAILED_LINE, loginTicker, PASSWORD_LINE } from './loginLines'
import { can3D } from './look'
import { SkynySlot } from './SkynySlot'

/** Lo que mide el Skyny del login: grande, protagonista de la pantalla. */
const SIZE_3D = 240
const SIZE_2D = 180

/**
 * Skyny del login (TD-A): en 3D (three, en un trozo aparte). Mientras carga deja su hueco vacío y
 * aparece con un fundido; sin WebGL, con movimiento reducido o si el 3D falla, el Skyny 2D.
 *
 * En 3D saluda con «HOLA» y luego pasan letreros por su pantalla (`loginTicker`: el saludo de la
 * hora y los de la casa). Al escribir la contraseña se tapa los ojos y dice «NO MIRO, PROMETIDO»;
 * si la entrada falla, «UPS, OTRA VEZ». Se puede tocar (¡boop!) y girar arrastrando.
 */
export function LoginSkyny({
  pose,
  typingPassword = false,
  failures = 0,
}: {
  pose?: Pose
  /** El foco está en la contraseña. */
  typingPassword?: boolean
  /** Cuántas veces ha fallado la entrada. */
  failures?: number
}) {
  const [three, setThree] = useState(can3D)
  const [ticker] = useState(() => ({ ...loginTicker(new Date()), delay: 2600 }))
  // Lo último que ha pasado manda: la contraseña, un fallo o, al llegar, el saludo.
  const [said, setSaid] = useState({
    text: 'HOLA' as string | string[],
    key: 0,
    hold: 2600,
    error: false,
    typing: false,
    failures: 0,
  })
  if (typingPassword !== said.typing || failures !== said.failures) {
    const failed = failures > said.failures
    setSaid({
      text: failed ? FAILED_LINE : PASSWORD_LINE,
      key: said.key + (failed || typingPassword ? 1 : 0),
      error: failed,
      hold: 900,
      typing: typingPassword,
      failures,
    })
  }
  // En 3D, primero dice que no mira y luego se tapa los ojos.
  const [covered, setCovered] = useState(false)
  useEffect(() => {
    if (!typingPassword) return
    const timer = window.setTimeout(() => setCovered(true), COVER_AFTER_MS)
    return () => {
      window.clearTimeout(timer)
      setCovered(false)
    }
  }, [typingPassword])
  const flat = <Companion state="calm" size={SIZE_2D} look pose={pose} />
  if (!three) return flat
  return (
    <Suspense fallback={<SkynySlot size={SIZE_3D} />}>
      <Skyny3D
        size={SIZE_3D}
        pose={pose === 'cover' && !covered ? undefined : pose}
        ticker={ticker}
        say={said}
        interactive
        onFail={() => setThree(false)}
      />
    </Suspense>
  )
}
