/** Margen de Skyny flotando con los bordes de la pantalla. */
const MARGIN = 12

/** Dónde está, en fracciones del sitio libre (0 a 1): sigue en su rincón al cambiar la ventana. */
export interface FloatPos {
  x: number
  y: number
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))

/** Posición en px (esquina superior izquierda) para una posición guardada y un tamaño de ventana. */
export function floatBox(
  pos: FloatPos,
  view: { w: number; h: number },
  size: number,
  bottom = 0,
): { left: number; top: number } {
  const freeW = Math.max(0, view.w - size - MARGIN * 2)
  const freeH = Math.max(0, view.h - size - MARGIN * 2 - bottom)
  return { left: MARGIN + pos.x * freeW, top: MARGIN + pos.y * freeH }
}

/** La posición guardada que corresponde a una esquina en px, dentro de los bordes. */
export function floatPos(
  left: number,
  top: number,
  view: { w: number; h: number },
  size: number,
  bottom = 0,
): FloatPos {
  const freeW = Math.max(1, view.w - size - MARGIN * 2)
  const freeH = Math.max(1, view.h - size - MARGIN * 2 - bottom)
  return { x: clamp01((left - MARGIN) / freeW), y: clamp01((top - MARGIN) / freeH) }
}

/** Qué hace un arrastre sobre Skyny flotando: girarlo sobre su centro o moverlo por la página. */
export type FloatGesture = 'rotate' | 'move'

/**
 * El gesto que empieza con este botón. Con ratón, el izquierdo gira y el derecho mueve. Con el
 * dedo (o un lápiz), mueve salvo con «Girar» activado. Otros botones no hacen nada.
 */
export function floatGesture(
  pointerType: string,
  button: number,
  rotateMode: boolean,
): FloatGesture | null {
  if (pointerType === 'mouse') return button === 0 ? 'rotate' : button === 2 ? 'move' : null
  if (button !== 0) return null
  return rotateMode ? 'rotate' : 'move'
}

/** Radianes de giro por píxel arrastrado; la inclinación (eje X) no pasa de `MAX_PITCH`. */
const ORBIT_PER_PX = 0.012
const MAX_PITCH = 1.1

/**
 * La orientación tras arrastrar `dx`, `dy` px: en horizontal gira sobre el eje Y hacia donde
 * arrastras y en vertical se inclina sobre el X. Se aplica a la cámara, que orbita al revés.
 */
export function orbitAfter(
  orbit: { yaw: number; pitch: number },
  dx: number,
  dy: number,
): { yaw: number; pitch: number } {
  const pitch = orbit.pitch + dy * ORBIT_PER_PX
  return {
    yaw: orbit.yaw - dx * ORBIT_PER_PX,
    pitch: Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch)),
  }
}
