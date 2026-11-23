import { useContext, useEffect, useRef } from 'react'
import { prefersReducedMotion } from '../look'
import { useOutfit, type Outfit } from '../outfit'
import { SkynyPaused } from '../pause'
import { SkynyScene, type Pose3D } from './scene'
import type { PulseType } from '../pulse'
import { attachSpin } from './spin'
import type { SkynyState } from './states'
import { onThemeChange, readColors } from './tokens'

/** Lo que cuenta Skyny en vivo (SV-B): letreros al cambiar de estado y contador. */
export interface LiveInfo {
  /** Letrero propio para el estado actual (p. ej. «REINTENTO 1/2»); sin él, el del estado. */
  message?: string
  /** Contador de Ejecutando: [hechos, total]. */
  progress?: [number, number] | null
  /** Letreros cortos (Skyny pequeño). */
  compact?: boolean
}

/** Cuánto más grande que Skyny es el lienzo por defecto: sitio para globos y complementos. */
export const ROOM = 1.5

/** Las microexpresiones del estudio v5. */
export type MicroType = 'nod' | 'shake' | 'curious' | 'startle' | 'sigh' | 'glance'

/** Entrar o salir de escena volando; se repite cada vez que cambia `key`. */
export interface StageMove {
  mode: 'enter' | 'exit'
  key: number
  /** Cuánto dura, en ms. */
  duration?: number
}

/**
 * Skyny en 3D (TD): flota, parpadea y sigue con la mirada al cursor y al foco, y se tapa los ojos
 * (`cover`) o mira de reojo (`peek`). Con `live` (SV-B), escribe en la pantalla lo que hace al
 * cambiar de estado, enseña el contador y solo sigue al cursor cuando pasa cerca de la cara. Va en
 * un trozo aparte (React.lazy) con three; si WebGL falla al arrancar o se pierde el contexto,
 * avisa con `onFail` para volver al 2D. Bajo la cortina del chat (`SkynyPaused`), se para.
 *
 * `size` es lo que ocupa en la página; el lienzo mide `size × room` y se sale por igual por los
 * cuatro lados (sin capturar clics), para que los globos, el sombrero o un gesto no se corten.
 * Aparece con un fundido.
 */
export default function Skyny3D({
  state = 'calm',
  size,
  room = ROOM,
  pose,
  live,
  outfit,
  replay = 0,
  boop = 0,
  ticker,
  say,
  interactive = false,
  stage,
  pulse,
  micro,
  scouter,
  onFail,
}: {
  state?: SkynyState
  size: number
  room?: number
  pose?: Pose3D
  live?: LiveInfo
  /** Gorro y complementos; por defecto, los de Ajustes › Companion. */
  outfit?: Outfit
  /** Al cambiar, repite el gesto de entrada del estado (Ajustes › Companion). */
  replay?: number
  /** Al cambiar, ¡boop!: un toque en la cara. */
  boop?: number
  /** Letreros que pasan deslizándose por la pantalla en reposo (login). */
  ticker?: { lines: string[]; loopFrom?: number; delay?: number } | null
  /** Un letrero ya, tecleado; sale otra vez cada vez que cambia `key`. */
  say?: { text: string | string[]; key: number; error?: boolean; hold?: number } | null
  /** Se puede tocar (¡boop!) y girar arrastrando; se marea si gira deprisa (login). */
  interactive?: boolean
  /** Entra o sale de escena (login). Si llega al montar, empieza ya con el movimiento. */
  stage?: StageMove | null
  /** Reacción a un evento en vivo sin cambiar de estado; sale cada vez que cambia `key`. */
  pulse?: { type: PulseType; key: number } | null
  /** Microexpresión de un momento (asentir, negar, curioso…); sale cada vez que cambia `key`. */
  micro?: { type: MicroType; key: number } | null
  /** Texto del scouter, si lo lleva puesto: se escribe como en una terminal. Sin él, el estado. */
  scouter?: string | null
  onFail: () => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const scene = useRef<SkynyScene | null>(null)
  const fail = useRef(onFail)
  const paused = useContext(SkynyPaused)
  const saved = useOutfit()
  const { hat, face, back } = outfit ?? saved
  const latest = useRef({
    state,
    live,
    paused,
    hat,
    face,
    back,
    ticker,
    say,
    stage,
    pulse,
    micro,
    scouter,
  })
  useEffect(() => {
    fail.current = onFail
    latest.current = {
      state,
      live,
      paused,
      hat,
      face,
      back,
      ticker,
      say,
      stage,
      pulse,
      micro,
      scouter,
    }
  })
  const isLive = live !== undefined
  const px = Math.round(size * room)
  const overflow = (px - size) / 2

  useEffect(() => {
    const el = canvas.current
    if (!el) return
    let skyny: SkynyScene
    try {
      skyny = new SkynyScene(el, readColors(), {
        gestures: !prefersReducedMotion(),
        messages: isLive,
      })
    } catch {
      fail.current()
      return
    }
    scene.current = skyny
    skyny.resize(px, px, room)
    const first = latest.current
    skyny.setHat(first.hat)
    skyny.setFace(first.face)
    skyny.setBack(first.back)
    if (first.scouter) skyny.setScouterText(first.scouter)
    if (first.ticker) skyny.setTicker(first.ticker.lines, first.ticker)
    if (first.say) skyny.say(first.say.text, first.say)
    if (first.live) {
      skyny.setCompact(!!first.live.compact)
      skyny.setProgress(first.live.progress?.[0] ?? null, first.live.progress?.[1])
      skyny.setState(first.state, { message: first.live.message })
    } else if (first.state !== 'calm') skyny.setState(first.state)
    if (first.stage) moveStage(skyny, first.stage)
    if (!first.paused) skyny.play()

    const lost = (e: Event) => {
      e.preventDefault()
      fail.current()
    }
    el.addEventListener('webglcontextlost', lost)
    const stopTheme = onThemeChange(() => skyny.setColors(readColors()))

    // En vivo, solo mira al cursor si pasa cerca de la cara; si no (login) o si se puede tocar
    // (login y cortina del chat), lo sigue por toda la página, también al tocar la pantalla; y en
    // el login, a lo que tiene el foco.
    const follow = !isLive || interactive
    const aim = (x: number, y: number) => {
      const box = el.getBoundingClientRect()
      const dx = x - (box.left + box.width / 2)
      const dy = y - (box.top + box.height / 2)
      skyny.lookAt(dx / 320, dy / 320)
    }
    const onMove = (e: PointerEvent) => {
      if (!follow) skyny.pointer(e.pointerType === 'mouse' ? { x: e.clientX, y: e.clientY } : null)
      else aim(e.clientX, e.clientY)
    }
    const onFocus = (e: FocusEvent) => {
      if (isLive || !(e.target instanceof Element)) return
      const r = e.target.getBoundingClientRect()
      aim(r.left + r.width / 2, r.top + r.height / 2)
    }
    window.addEventListener('pointermove', onMove, { passive: true })
    window.addEventListener('pointerdown', onMove, { passive: true })
    document.addEventListener('focusin', onFocus)

    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerdown', onMove)
      document.removeEventListener('focusin', onFocus)
      stopTheme()
      el.removeEventListener('webglcontextlost', lost)
      skyny.dispose()
      scene.current = null
    }
  }, [px, room, isLive, interactive])

  useEffect(() => {
    const host = canvas.current?.parentElement
    if (!interactive || !host || !scene.current) return
    return attachSpin(scene.current, host)
  }, [interactive, px, room, isLive])

  const tickerLines = ticker?.lines.join('\n')
  useEffect(() => {
    const t = latest.current.ticker
    scene.current?.setTicker(t?.lines ?? null, t ?? {})
  }, [tickerLines])

  const sayKey = say?.key
  useEffect(() => {
    const s = latest.current.say
    if (s && sayKey !== undefined) scene.current?.say(s.text, s)
  }, [sayKey])

  const stageKey = stage?.key
  const firstStageKey = useRef(stageKey)
  useEffect(() => {
    // El primero ya lo hizo la escena al crearse.
    if (stageKey === firstStageKey.current) return
    firstStageKey.current = undefined
    const st = latest.current.stage
    if (st && scene.current) moveStage(scene.current, st)
  }, [stageKey])

  const pulseKey = pulse?.key
  useEffect(() => {
    const p = latest.current.pulse
    if (p && !prefersReducedMotion()) scene.current?.pulseEvent(p.type)
  }, [pulseKey])

  const microKey = micro?.key
  useEffect(() => {
    const m = latest.current.micro
    if (m && microKey && !prefersReducedMotion()) scene.current?.microExpression(m.type)
  }, [microKey])

  useEffect(() => {
    if (paused) scene.current?.stop()
    else scene.current?.play()
  }, [paused])

  const message = live?.message
  useEffect(() => {
    if (scene.current && scene.current.state !== state)
      scene.current.setState(state, { message: latest.current.live?.message })
  }, [state, message])

  const done = live?.progress?.[0] ?? null
  const total = live?.progress?.[1]
  useEffect(() => {
    scene.current?.setProgress(done, total)
  }, [done, total])

  useEffect(() => {
    scene.current?.setPose(pose)
  }, [pose])

  useEffect(() => {
    if (replay) scene.current?.setState(latest.current.state)
  }, [replay])

  useEffect(() => {
    if (boop) scene.current?.poke()
  }, [boop])

  useEffect(() => {
    scene.current?.setScouterText(scouter ?? '')
  }, [scouter])

  useEffect(() => {
    scene.current?.setHat(hat)
    scene.current?.setFace(face)
    scene.current?.setBack(back)
  }, [hat, face, back])

  return (
    <canvas
      ref={canvas}
      className="skyny-3d"
      width={px}
      height={px}
      style={{ width: px, height: px, margin: -overflow }}
      data-state={state}
      data-pose={pose}
      aria-hidden="true"
    />
  )
}

function moveStage(skyny: SkynyScene, { mode, duration }: StageMove) {
  if (mode === 'enter') skyny.enter({ duration })
  else skyny.exit({ duration })
}
