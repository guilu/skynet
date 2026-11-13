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

/** Lo que dura el fundido entre dos estados (colores y postura). */
export const BLEND_MS = 300
/** Fundido de un estado a otro: 0 → 1 en `BLEND_MS`. */
export const blend = (ms: number) => smooth(ms / BLEND_MS)

/** El accesorio que se va se desvanece en 220 ms (1 → 0). */
export const accessoryFade = (ms: number) => 1 - smooth(ms / 220)

/** La mano derecha sube a enseñar su símbolo (Te necesita, En pausa, Sin conexión): 0 → 1. */
export const handLift = (ms: number) => 1 - (1 - clamp01(ms / 420)) ** 3

/**
 * En espera, cada 7,6 s Skyny mira un momento a la derecha (`gaze`, en píxeles de la pantalla) y
 * parpadea un poco después.
 */
export function restLook(ms: number) {
  const phase = ms % 7600
  const look = phase >= 1800 && phase <= 3400 ? Math.sin((Math.PI * (phase - 1800)) / 1600) ** 2 : 0
  return { gaze: Math.round(7 * look), blinking: phase >= 4900 && phase < 5040 }
}

/** Pensando, cada 9 s levanta la mirada hacia arriba a la izquierda, como quien piensa. */
export function thinkingLook(ms: number): [number, number] {
  const phase = ms % 9000
  const k = phase >= 1800 && phase < 5000 ? Math.sin((Math.PI * (phase - 1800)) / 3200) ** 2 : 0
  return [Math.round(-10 * k) || 0, Math.round(-6 * k) || 0]
}
