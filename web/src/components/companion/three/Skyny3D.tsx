import { useEffect, useRef } from 'react'
import { SkynyScene, type Pose3D, type SkynyColors } from './scene'

/**
 * Skyny en 3D (TD-A), en Reposo: flota, parpadea, sigue con la mirada al cursor y al foco, y se
 * tapa los ojos (`cover`) o mira de reojo (`peek`). Va en un trozo aparte (React.lazy) con three;
 * si WebGL falla al arrancar o se pierde el contexto, avisa con `onFail` para volver al 2D.
 */
export default function Skyny3D({
  size,
  pose,
  onFail,
}: {
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
      skyny = new SkynyScene(el, readColors())
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

    // Sigue al tema y a la paleta, como el editor (lib/monaco.ts).
    const recolor = () => skyny.setColors(readColors())
    const observer = new MutationObserver(recolor)
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'data-palette'],
    })
    const media = window.matchMedia?.('(prefers-color-scheme: dark)')
    media?.addEventListener('change', recolor)

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
      media?.removeEventListener('change', recolor)
      observer.disconnect()
      el.removeEventListener('webglcontextlost', lost)
      skyny.dispose()
      scene.current = null
    }
  }, [size])

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
      data-pose={pose}
      aria-hidden="true"
    />
  )
}

/** Tema oscuro: `data-theme` en `<html>` o, si no hay, el del sistema. */
function isDark() {
  const theme = document.documentElement.getAttribute('data-theme')
  if (theme) return theme === 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

let probe: CanvasRenderingContext2D | null | undefined

/** Un color CSS cualquiera (hex, rgb, color-mix…) como lo entiende three. */
function toHex(color: string, fallback: string) {
  probe ??= document.createElement('canvas').getContext('2d')
  if (!probe || !color) return fallback
  probe.fillStyle = fallback
  probe.fillStyle = color
  return probe.fillStyle
}

function readColors(): SkynyColors {
  const style = getComputedStyle(document.documentElement)
  const token = (name: string, fallback: string) =>
    toHex(style.getPropertyValue(name).trim(), fallback)
  return {
    body: token('--primary', '#335fe0'),
    band: token('--companion-band', '#1f2647'),
    node: token('--companion-node', '#ffffff'),
    glow: token('--companion-glow', '#49c2ff'),
    core: token('--companion-eye', '#c9f1ff'),
    dark: isDark(),
  }
}
