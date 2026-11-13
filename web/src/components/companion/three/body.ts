// Forma del cuerpo de Skyny 3D (TD-A): una esfera deformada en «onigiri» y cómo apoyar las
// bandas sobre ella. Solo matemáticas, sin WebGL, para poder probarlo en jsdom.

/** Radio de la esfera de partida. */
export const BODY_RADIUS = 0.78

export type Vec3 = [number, number, number]

/**
 * Deforma un punto de la esfera de radio `BODY_RADIUS` hacia el cuerpo: estrecha hacia el ápice,
 * aplana el perfil, recorta la base y marca una cresta suave en la espalda. Es la misma fórmula
 * que el estudio aplicaba vértice a vértice, ahora como función continua.
 */
export function deform([x, y, z]: Vec3): Vec3 {
  const yNorm = y / BODY_RADIUS
  if (Math.abs(yNorm) > 0.999999) {
    // Los polos de la esfera UV se repiten: todos al mismo punto.
    x = 0
    z = 0
  }
  x *= (0.44 + (1 - yNorm) * 0.48) * 1.15
  z *= 0.42 + (1 - yNorm) * 0.2
  if (y < -0.22) y -= (y + 0.22) * 0.28
  const dorsal = (Math.max(0, -z) / 0.64) ** 2
  z -= Math.cos(x * 2.2) * 0.04 * (1 - Math.abs(yNorm)) * dorsal
  return [x, y, z]
}

const length = ([x, y, z]: Vec3) => Math.hypot(x, y, z)
const unit = (v: Vec3): Vec3 => {
  const l = length(v) || 1
  return [v[0] / l, v[1] / l, v[2] / l]
}

/**
 * El punto del cuerpo en la dirección `direction` desde el centro, separado `clearance` hacia
 * fuera. El estudio lo buscaba lanzando un rayo contra los 10.000 triángulos de la malla (casi
 * un segundo por banda); aquí se invierte la deformación con unas pocas iteraciones.
 */
export function surfaceAt(direction: Vec3, clearance = 0): Vec3 {
  const d = unit(direction)
  let p = d
  for (let i = 0; i < 40; i++) {
    const hit = unit(deform([p[0] * BODY_RADIUS, p[1] * BODY_RADIUS, p[2] * BODY_RADIUS]))
    const err: Vec3 = [d[0] - hit[0], d[1] - hit[1], d[2] - hit[2]]
    if (length(err) < 1e-7) break
    p = unit([p[0] + err[0], p[1] + err[1], p[2] + err[2]])
  }
  const r = length(deform([p[0] * BODY_RADIUS, p[1] * BODY_RADIUS, p[2] * BODY_RADIUS]))
  return [d[0] * (r + clearance), d[1] * (r + clearance), d[2] * (r + clearance)]
}

/** Busca la cáscara trasera: la `z` donde un rayo que va de atrás hacia delante por (x, y) entra en el
 * cuerpo. El estudio lo hacía con un raycaster contra la malla; aquí, por bisección. */
export function rearShellZ(x: number, y: number): number {
  const inside = (z: number) => {
    const p: Vec3 = [x, y, z]
    return length(p) <= length(surfaceAt(p))
  }
  let out = -1.2
  let into = 0
  if (!inside(into)) throw new Error(`(${x}, ${y}) no cae sobre la espalda de Skyny`)
  for (let i = 0; i < 40; i++) {
    const mid = (out + into) / 2
    if (inside(mid)) into = mid
    else out = mid
  }
  return (out + into) / 2
}

/** Los tres bordes de la hebilla de la espalda, donde acaba cada banda (abajo, derecha, izquierda). */
export const BUCKLE_EDGES: [Vec3, Vec3, Vec3] = [
  [0, -0.145, -0.54],
  [0.108, 0.044, -0.53],
  [-0.108, 0.044, -0.53],
]

/** Recorridos de las tres bandas: nacen en la cara y acaba cada una en su borde de la hebilla. */
export const BANDS: Vec3[][] = [
  // Inferior: sube desde la base por la espalda.
  [
    [0, -0.42, 0.51],
    [0, -0.58, 0.38],
    [0, -0.66, 0.12],
    [0, -0.64, -0.16],
    [0, -0.46, -0.32],
    [0, -0.22, -0.36],
    BUCKLE_EDGES[0],
  ],
  // Derecha y la izquierda, simétrica: por el hombro hacia la hebilla.
  ...[1, -1].map((s, i): Vec3[] => [
    [s * 0.28, 0.22, 0.49],
    [s * 0.42, 0.29, 0.36],
    [s * 0.53, 0.32, 0.14],
    [s * 0.49, 0.282, -0.14],
    [s * 0.32, 0.176, -0.34],
    [s * 0.23, 0.12, -0.45],
    BUCKLE_EDGES[i + 1],
  ]),
]
