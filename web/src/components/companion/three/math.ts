// Curvas y ayudas numéricas de las animaciones (estudio v4.3).
export const clamp01 = (v: number) => Math.max(0, Math.min(1, v))
export const smoothstep = (v: number) => {
  const t = clamp01(v)
  return t * t * (3 - 2 * t)
}
export const easeOutCubic = (ms: number) => 1 - Math.pow(1 - clamp01(ms / 420), 3)
