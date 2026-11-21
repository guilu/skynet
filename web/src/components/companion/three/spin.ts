import type { SkynyScene } from './scene'

/** Radianes de giro por píxel arrastrado. */
const PER_PX = 0.012

/**
 * Skyny se puede tocar y girar (login): un clic corto encima es un ¡boop!; arrastrando, la cámara
 * gira a su alrededor, sigue con la inercia al soltar y vuelve sola al frente. Si lo giras
 * deprisa, se marea (lo detecta la escena). Escucha en `host`, el hueco de Skyny en la página: el
 * lienzo no captura clics porque se sale de su hueco. Devuelve cómo soltarlo.
 */
export function attachSpin(scene: SkynyScene, host: HTMLElement): () => void {
  let angle = 0
  let velocity = 0
  let drag: {
    x: number
    y: number
    t: number
    lastX: number
    lastT: number
    moved: boolean
  } | null = null
  let frame = 0
  let last = 0

  const settle = (now: number) => {
    const dt = Math.min(0.05, (now - last) / 1000)
    last = now
    if (!drag) {
      angle += velocity * dt
      velocity *= Math.pow(0.06, dt)
      angle *= Math.pow(0.12, dt)
      if (Math.abs(angle) < 0.001 && Math.abs(velocity) < 0.01) {
        angle = 0
        velocity = 0
        scene.setOrbit(0)
        frame = 0
        return
      }
    }
    scene.setOrbit(angle)
    frame = requestAnimationFrame(settle)
  }
  const run = () => {
    if (frame) return
    last = performance.now()
    frame = requestAnimationFrame(settle)
  }

  const down = (e: PointerEvent) => {
    if (e.button !== 0) return
    const now = performance.now()
    drag = { x: e.clientX, y: e.clientY, t: now, lastX: e.clientX, lastT: now, moved: false }
    velocity = 0
    host.setPointerCapture?.(e.pointerId)
    run()
  }
  const move = (e: PointerEvent) => {
    if (!drag) return
    const now = performance.now()
    const dx = e.clientX - drag.lastX
    if (Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 6) drag.moved = true
    angle += dx * PER_PX
    const dt = Math.max(1, now - drag.lastT) / 1000
    velocity = velocity * 0.6 + ((dx * PER_PX) / dt) * 0.4
    drag.lastX = e.clientX
    drag.lastT = now
  }
  const up = (e: PointerEvent) => {
    if (!drag) return
    const quick = !drag.moved && performance.now() - drag.t < 320
    if (performance.now() - drag.lastT > 80) velocity = 0
    drag = null
    if (quick && scene.hitTest(e.clientX, e.clientY)) scene.poke()
  }

  host.addEventListener('pointerdown', down)
  host.addEventListener('pointermove', move)
  host.addEventListener('pointerup', up)
  host.addEventListener('pointercancel', up)
  return () => {
    cancelAnimationFrame(frame)
    host.removeEventListener('pointerdown', down)
    host.removeEventListener('pointermove', move)
    host.removeEventListener('pointerup', up)
    host.removeEventListener('pointercancel', up)
  }
}
