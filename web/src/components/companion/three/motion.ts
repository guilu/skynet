// Curvas de los gestos de Skyny 3D (portadas del estudio `skyny-studio.html`). Todas reciben los
// milisegundos desde que empezó el gesto y devuelven un valor sin unidades.

/** Lo que dura el gesto de entrada de un estado; después solo queda el flotar. */
export const ENTRY_MS = 1450

const clamp01 = (u: number) => Math.max(0, Math.min(1, u))
/** Suavizado `smoothstep`: 0 → 1 sin tirones en los extremos. */
export const smooth = (u: number) => {
  const x = clamp01(u)
  return x * x * (3 - 2 * x)
}

/** Sube en 420 ms, aguanta y baja hacia los 950–1450 ms. */
export const entryGesture = (ms: number) => smooth(ms / 420) * (1 - smooth((ms - 950) / 500))

/** Aparición escalonada del accesorio número `index` (burbujas, zetas, rayos…). */
export const reveal = (ms: number, index: number) => smooth((ms - index * 230) / 280)

/** Empujón de Ejecutando: 0 → 1 → 0 en 680 ms. */
export const executionBurst = (ms: number) => Math.sin(Math.PI * clamp01(ms / 680)) ** 2

/** Carrerilla de Ejecutando: atrás, adelante y vuelta al sitio. */
export const executionTravel = (ms: number) => {
  const step = (from: number, to: number) => smooth((ms - from) / (to - from))
  return -0.18 * step(0, 260) + 0.58 * step(260, 620) - 0.4 * step(670, 1250)
}

/** Sacudida de Error, que se apaga sola. */
export const errorShake = (ms: number) => {
  const u = clamp01(ms / 680)
  return 0.085 * Math.sin(u * 6 * Math.PI) * Math.sin(Math.PI * u) ** 2
}

/** Salto de Éxito: una parábola de 620 ms. */
export const successHop = (ms: number) => {
  const u = clamp01(ms / 620)
  return 0.23 * 4 * u * (1 - u)
}

/** El «?» de Te necesita salta con un pequeño rebote. */
export const popIn = (ms: number) => {
  const u = clamp01(ms / 320)
  return 1 + 2.5 * (u - 1) ** 3 + 1.5 * (u - 1) ** 2
}
