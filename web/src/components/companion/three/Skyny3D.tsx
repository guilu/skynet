import { useEffect, useRef } from 'react'
import { prefersReducedMotion } from '../look'
import type { CompanionState } from '../state'
import { SkynyScene, type Pose3D } from './scene'
import { onThemeChange, readColors } from './tokens'

/**
 * Skyny en 3D (TD): flota, parpadea, sigue con la mirada al cursor y al foco, y se tapa los ojos
 * (`cover`) o mira de reojo (`peek`). Va en un trozo aparte (React.lazy) con three; si WebGL falla
 * al arrancar o se pierde el contexto, avisa con `onFail` para volver al 2D.
 */
export default function Skyny3D({
  state = 'calm',
  size,
  pose,
  onFail,
}: {
  state?: CompanionState
  size: number
  pose?: Pose3D
  onFail: () => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const scene = useRef<SkynyScene | null>(null)
  const fail = useRef(onFail)
  useEffect(() => {
    fail.current = onFail
  }, [onFail])

  useEffect(() => {
    const el = canvas.current
    if (!el) return
    let skyny: SkynyScene
    try {
      skyny = new SkynyScene(el, readColors(), { gestures: !prefersReducedMotion() })
    } catch {
      fail.current()
      return
    }
    scene.current = skyny
    skyny.resize(size)
    skyny.play()

    const lost = (e: Event) => {
      e.preventDefault()
      fail.current()
    }
    el.addEventListener('webglcontextlost', lost)
    const stopTheme = onThemeChange(() => skyny.setColors(readColors()))

    // Mira hacia el cursor o hacia lo que tiene el foco.
    const aim = (x: number, y: number) => {
      const box = el.getBoundingClientRect()
      const dx = x - (box.left + box.width / 2)
      const dy = y - (box.top + box.height / 2)
      skyny.lookAt(dx / 320, dy / 320)
    }
    const onMove = (e: PointerEvent) => aim(e.clientX, e.clientY)
    const onFocus = (e: FocusEvent) => {
      if (!(e.target instanceof Element)) return
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
  }, [size])

  useEffect(() => {
    if (scene.current && scene.current.current !== state) scene.current.setState(state)
  }, [state])

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
