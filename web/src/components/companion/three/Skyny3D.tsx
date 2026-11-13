import { useEffect, useRef } from 'react'
import { prefersReducedMotion } from '../look'
import type { CompanionState } from '../state'
import { SkynyScene, type Pose3D } from './scene'
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

/**
 * Skyny en 3D (TD): flota, parpadea y sigue con la mirada al cursor y al foco, y se tapa los ojos
 * (`cover`) o mira de reojo (`peek`). Con `live` (SV-B), escribe en la pantalla lo que hace al
 * cambiar de estado, enseña el contador y solo sigue al cursor cuando pasa cerca de la cara. Va en
 * un trozo aparte (React.lazy) con three; si WebGL falla al arrancar o se pierde el contexto,
 * avisa con `onFail` para volver al 2D.
 */
export default function Skyny3D({
  state = 'calm',
  size,
  pose,
  live,
  onFail,
}: {
  state?: CompanionState
  size: number
  pose?: Pose3D
  live?: LiveInfo
  onFail: () => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const scene = useRef<SkynyScene | null>(null)
  const fail = useRef(onFail)
  const latest = useRef({ state, live })
  useEffect(() => {
    fail.current = onFail
    latest.current = { state, live }
  })
  const isLive = live !== undefined

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
    skyny.resize(size)
    const first = latest.current
    if (first.live) {
      skyny.setCompact(!!first.live.compact)
      skyny.setProgress(first.live.progress?.[0] ?? null, first.live.progress?.[1])
      skyny.setState(first.state, { message: first.live.message })
    } else if (first.state !== 'calm') skyny.setState(first.state)
    skyny.play()

    const lost = (e: Event) => {
      e.preventDefault()
      fail.current()
    }
    el.addEventListener('webglcontextlost', lost)
    const stopTheme = onThemeChange(() => skyny.setColors(readColors()))

    // En vivo, solo mira al cursor si pasa cerca de la cara; si no (login), lo sigue por toda la
    // página, y también a lo que tiene el foco.
    const aim = (x: number, y: number) => {
      const box = el.getBoundingClientRect()
      const dx = x - (box.left + box.width / 2)
      const dy = y - (box.top + box.height / 2)
      skyny.lookAt(dx / 320, dy / 320)
    }
    const onMove = (e: PointerEvent) => {
      if (isLive) skyny.pointer(e.pointerType === 'mouse' ? { x: e.clientX, y: e.clientY } : null)
      else aim(e.clientX, e.clientY)
    }
    const onFocus = (e: FocusEvent) => {
      if (isLive || !(e.target instanceof Element)) return
      const r = e.target.getBoundingClientRect()
      aim(r.left + r.width / 2, r.top + r.height / 2)
    }
    window.addEventListener('pointermove', onMove, { passive: true })
    document.addEventListener('focusin', onFocus)

    return () => {
      window.removeEventListener('pointermove', onMove)
      document.removeEventListener('focusin', onFocus)
      stopTheme()
      el.removeEventListener('webglcontextlost', lost)
      skyny.dispose()
      scene.current = null
    }
  }, [size, isLive])

  const message = live?.message
  useEffect(() => {
    if (scene.current && scene.current.current !== state)
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

  return (
    <canvas
      ref={canvas}
      className="skyny-3d"
      width={size}
      height={size}
      style={{ width: size, height: size }}
      data-state={state}
      data-pose={pose}
      aria-hidden="true"
    />
  )
}
