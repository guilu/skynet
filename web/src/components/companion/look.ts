// Los ojos de Skyny siguen al cursor (SY-C): cuánto y hacia dónde se mueven.

/** Cuánto se mueven los ojos como mucho al seguir al cursor, en unidades del dibujo. */
const LOOK_MAX = 4
/** Dónde cae el visor dentro de la caja del dibujo grande (viewBox -24 -22 176 154). */
const VISOR_AT = [(60 + 24) / 176, (64 + 22) / 154] as const

/** Hacia dónde mueve los ojos un punto de la pantalla: más cuanto más lejos, hasta `LOOK_MAX`. */
export function lookOffset(
  box: Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>,
  x: number,
  y: number,
): [number, number] {
  const vx = x - (box.left + box.width * VISOR_AT[0])
  const vy = y - (box.top + box.height * VISOR_AT[1])
  const d = Math.hypot(vx, vy)
  if (d < 1) return [0, 0]
  const reach = LOOK_MAX * Math.min(1, d / 200)
  return [round((vx / d) * reach), round((vy / d) * reach)]
}

const round = (n: number) => Math.round(n * 10) / 10

export const prefersReducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false
