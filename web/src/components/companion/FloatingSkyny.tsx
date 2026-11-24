import { Suspense, useEffect, useRef, useState, type PointerEvent } from 'react'
import { useMediaQuery } from '../../useMediaQuery'
import { useCompanion } from './context'
import { Skyny3D } from './load3d'
import { can3D } from './look'
import { setCompanionPrefs, useCompanionPrefs } from './prefs'
import { useSettledState } from './settle'
import { FLOAT_ENTER_MS, FLOAT_EXIT_MS } from '../chat/curtainSequence'
import { floatBox, floatPos } from './float'
import { takeQuip } from './gesture'
import { sayOnScouter, useScouterText } from './scouter'
import type { MicroType } from './three/Skyny3D'

/** Lo que mide Skyny flotando, y el margen con los bordes de la pantalla. */
const SIZE = 240
const SIZE_MOBILE = 168
/**
 * Con texto en el scouter, Skyny entero crece hasta este tanto (sin recortarlo) para que se lea.
 * Se pinta siempre a ese tamaño y se encoge con CSS: así crecer es suave y no se ve borroso.
 */
const GROW = 1.75
/** En el móvil, la barra de navegación de abajo: Skyny no se mete debajo. */
const MOBILE_NAV = 84
/** Lo que espera tras irse volando antes de desmontarlo (sin gastar GPU mientras no se ve). */
const LEAVE_MS = FLOAT_EXIT_MS + 200
/** Menos que esto (px y ms) es un toque (¡boop!), no un arrastre. */
const CLICK_PX = 6
const CLICK_MS = 320

function useViewport() {
  const read = () => ({ w: window.innerWidth, h: window.innerHeight })
  const [view, setView] = useState(read)
  useEffect(() => {
    const onResize = () => setView(read())
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  return view
}

/**
 * Skyny flotando por encima de la web, en su propia capa: entra volando al cargar, cuenta con su
 * cara el estado global, sigue con los ojos al puntero y al foco, y reacciona cuando lo tocas
 * (¡boop!). Se arrastra a cualquier sitio dentro de la pantalla para quitarlo de en medio, y
 * recuerda dónde lo dejaste. Solo él recibe clics: el resto de la capa deja pasar todo a la web.
 * Con el scouter puesto, en su cristal sale lo que dice Skyny (`sayOnScouter`) o, si no dice nada,
 * el estado.
 *
 * Con `away` (la cortina del chat abierta) se va volando y se desmonta; al cerrarla vuelve.
 * Sin WebGL o con movimiento reducido no sale (el 2D de la barra ya cuenta lo mismo).
 */
export function FloatingSkyny({ away = false }: { away?: boolean }) {
  const prefs = useCompanionPrefs()
  const [three, setThree] = useState(can3D)
  const { expression } = useCompanion()
  const state = useSettledState(expression)
  const mobile = useMediaQuery('(max-width: 720px)')
  const size = mobile ? SIZE_MOBILE : SIZE
  const bottom = mobile ? MOBILE_NAV : 0
  const view = useViewport()
  const scouter = useScouterText()

  // Montado mientras se ve o mientras se va volando.
  const [mounted, setMounted] = useState(!away)
  const [moves, setMoves] = useState(1)
  const [shownAway, setShownAway] = useState(away)
  if (away !== shownAway) {
    setShownAway(away)
    setMoves((n) => n + 1)
    if (!away) setMounted(true)
  }
  useEffect(() => {
    if (!away) return
    const timer = window.setTimeout(() => setMounted(false), LEAVE_MS)
    return () => window.clearTimeout(timer)
  }, [away])

  // Arrastrar: mientras dura, la posición va en px; al soltar se guarda en fracciones.
  const [dragBox, setDragBox] = useState<{ left: number; top: number } | null>(null)
  const drag = useRef<{ dx: number; dy: number; x: number; y: number; at: number; moved: boolean }>(
    null,
  )
  const [boop, setBoop] = useState(0)
  const [micro, setMicro] = useState<{ type: MicroType; key: number } | null>(null)
  const react = (type: MicroType) => setMicro((m) => ({ type, key: (m?.key ?? 0) + 1 }))

  if (!prefs.floating || !three || !mounted) return null

  const box = dragBox ?? floatBox(prefs.floatAt, view, size, bottom)
  // Crece hacia el centro de la pantalla: desde la esquina o el borde donde está.
  const at = dragBox ? floatPos(box.left, box.top, view, size, bottom) : prefs.floatAt
  const big = Math.round(size * GROW)
  const zoomed = scouter !== '' && prefs.face === 'cyber' && !away

  const onDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return
    e.currentTarget.setPointerCapture(e.pointerId)
    drag.current = {
      dx: e.clientX - box.left,
      dy: e.clientY - box.top,
      x: e.clientX,
      y: e.clientY,
      at: performance.now(),
      moved: false,
    }
  }
  const onMove = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d) return
    if (!d.moved && Math.hypot(e.clientX - d.x, e.clientY - d.y) < CLICK_PX) return
    if (!d.moved) react('startle')
    d.moved = true
    const p = floatPos(e.clientX - d.dx, e.clientY - d.dy, view, size, bottom)
    setDragBox(floatBox(p, view, size, bottom))
  }
  const onUp = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    drag.current = null
    if (!d) return
    if (!d.moved) {
      if (performance.now() - d.at < CLICK_MS) {
        setBoop((n) => n + 1)
        // Con el scouter puesto, al tocarlo dice la siguiente frase en el cristal.
        if (prefs.face === 'cyber') sayOnScouter(takeQuip())
      }
      return
    }
    setCompanionPrefs({ floatAt: floatPos(e.clientX - d.dx, e.clientY - d.dy, view, size, bottom) })
    setDragBox(null)
    react('nod')
  }

  return (
    <div
      className="floating-skyny"
      data-dragging={dragBox ? true : undefined}
      data-away={away || undefined}
      style={{ left: box.left, top: box.top, width: size, height: size }}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      onPointerCancel={() => {
        drag.current = null
        setDragBox(null)
      }}
      aria-hidden="true"
    >
      <div
        className="floating-skyny-zoom"
        data-zoom={zoomed || undefined}
        style={{
          width: big,
          height: big,
          left: -(big - size) * at.x,
          top: -(big - size) * at.y,
          transformOrigin: `${at.x * 100}% ${at.y * 100}%`,
          transform: zoomed ? undefined : `scale(${1 / GROW})`,
        }}
      >
        <Suspense fallback={null}>
          <Skyny3D
            state={state}
            size={big}
            scouterZoom={false}
            boop={boop}
            micro={micro}
            scouter={scouter}
            stage={{
              mode: away ? 'exit' : 'enter',
              key: moves,
              duration: away ? FLOAT_EXIT_MS : FLOAT_ENTER_MS,
            }}
            onFail={() => setThree(false)}
          />
        </Suspense>
      </div>
    </div>
  )
}
