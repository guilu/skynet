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
