// Accesorios 3D de cada estado (sección 10 del estudio): burbujas, líneas de velocidad, lupa,
// confeti, rayos de estrés, «?», pausa, zetas y señal tachada. Las posiciones son las del estudio.
import {
  BoxGeometry,
  CatmullRomCurve3,
  Color,
  CylinderGeometry,
  DoubleSide,
  ExtrudeGeometry,
  Group,
  LatheGeometry,
  Mesh,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  MeshStandardMaterial,
  Path,
  PlaneGeometry,
  Shape,
  SphereGeometry,
  Vector2,
  Vector3,
  type Material,
  type Object3D,
} from 'three'
import type { Accessory } from './states'

/** Colores que usan los accesorios. */
export interface AccessoryColors {
  /** El del cuerpo en ese estado. */
  body: string
  /** Confeti de Éxito. */
  confetti: string[]
}

/** Dónde agarra la lupa la mano derecha (antes del gesto de Revisando). */
export const GRIP_AT = new Vector3(1.06, -0.42, 0.4)
/** Donde van los marcadores pequeños (pausa, «?», sin conexión), arriba a la derecha. */
export const MARKER_AT = new Vector3(0.89, 0.48, 0.34)
/**
 * Accesorios que Skyny sostiene en alto con la mano derecha (estudio v2), con su tamaño: el «?»
 * de Te necesita, la pausa y la señal tachada de Sin conexión.
 */
export const HAND_SYMBOLS: Partial<Record<Accessory, number>> = {
  alert: 0.52,
  pause: 1,
  offline: 1.25,
}

/** Un símbolo plano (sin bisel) con el grosor `depth`, centrado en z. */
const flatSymbol = (shape: Shape, depth: number, material: Material) =>
  new Mesh(
    new ExtrudeGeometry(shape, { depth, bevelEnabled: false, curveSegments: 48 }).translate(
      0,
      0,
      -depth / 2,
    ),
    material,
  )
/** Posición de partida de cada línea de velocidad. */
export const SPEED_X = -1.35

/** Plástico como el del cuerpo (acabado del estudio v2), en otro color. */
const plastic = (color: string | number) =>
  new MeshPhysicalMaterial({
    color: new Color(color),
    roughness: 0.38,
    metalness: 0,
    clearcoat: 0.22,
    clearcoatRoughness: 0.3,
  })

/**
 * El accesorio de un estado, como grupo con un hijo por pieza (en el orden en que aparecen). La
 * lupa va dentro de un agarre (`name = 'grip'`) que la mano mueve durante el gesto.
 */
export function buildAccessory(type: Accessory | null, colors: AccessoryColors): Group {
  const group = new Group()
  switch (type) {
    case 'bubbles': {
      const material = plastic(colors.body)
      material.roughness = 0.1
      material.clearcoat = 0.9
      for (const [r, x, y] of [
        [0.08, 0.72, 0.72],
        [0.13, 0.88, 0.98],
        [0.22, 1.12, 1.34],
      ]) {
        const bubble = new Mesh(new SphereGeometry(r, 24, 24), material)
        bubble.position.set(x, y, 0.2)
        group.add(bubble)
      }
      break
    }
    case 'magnifier': {
      const lens = new Group()
      const rim = new Mesh(
        new LatheGeometry(
          [
            [0.235, -0.016],
            [0.235, 0.016],
            [0.239, 0.02],
            [0.281, 0.02],
            [0.285, 0.016],
            [0.285, -0.016],
            [0.281, -0.02],
            [0.239, -0.02],
            [0.235, -0.016],
          ].map(([x, y]) => new Vector2(x, y)),
          96,
        ).rotateX(Math.PI / 2),
        new MeshStandardMaterial({
          color: 0xdce3ed,
          roughness: 0.24,
          metalness: 0.85,
          side: DoubleSide,
        }),
      )
      const glass = new Mesh(
        new CylinderGeometry(0.24, 0.24, 0.02, 32),
        new MeshPhysicalMaterial({ color: 0x38bdf8, transparent: true, opacity: 0.5 }),
      )
      glass.rotation.x = Math.PI / 2
      const handle = new Mesh(
        new CylinderGeometry(0.04, 0.04, 0.35, 16),
        new MeshStandardMaterial({ color: new Color(colors.body).multiplyScalar(0.45) }),
      )
      // El mango sale del aro hacia abajo a la derecha, un poco por detrás del frente.
      const direction = new Vector3(1, -1, 0).normalize()
      handle.position.copy(direction).multiplyScalar(0.28 + 0.35 / 2)
      handle.position.z = -0.025
      handle.quaternion.setFromUnitVectors(new Vector3(0, 1, 0), direction)
      lens.add(rim, glass, handle)
      // Toda la lupa gira para que el cristal quede fuera del nodo, y se cuelga del mango.
      lens.rotation.z = -Math.PI / 2
      const grip = handle.position.clone().applyAxisAngle(new Vector3(0, 0, 1), lens.rotation.z)
      lens.position.copy(grip).negate()
      const holder = new Group()
      holder.name = 'grip'
      holder.position.copy(GRIP_AT)
      holder.add(lens)
      group.add(holder)
      break
    }
    case 'speed': {
      const material = new MeshBasicMaterial({ color: new Color(colors.body) })
      for (let i = 0; i < 3; i++) {
        const bar = new Mesh(new BoxGeometry(0.65 - i * 0.1, 0.05, 0.04), material)
        bar.position.set(SPEED_X - i * 0.1, 0.1 - i * 0.14, 0.05)
        group.add(bar)
      }
      break
    }
    case 'offline': {
      const material = plastic(0x64748b)
      const marker = new Group()
      const ring = new Shape().absarc(0, 0, 0.166, 0, Math.PI * 2, false)
      ring.holes.push(new Path().absarc(0, 0, 0.114, 0, Math.PI * 2, true))
      const slash = new Mesh(new BoxGeometry(0.263, 0.05, 0.038), material)
      slash.rotation.z = -Math.PI / 4
      marker.add(flatSymbol(ring, 0.034, material), slash)
      marker.position.copy(MARKER_AT)
      group.add(marker)
      break
    }
    case 'zzz': {
      const material = plastic(0x64748b)
      for (let i = 0; i < 3; i++) {
        const letter = new Group()
        for (const y of [-0.065, 0.065]) {
          const bar = new Mesh(new BoxGeometry(0.16, 0.035, 0.035), material)
          bar.position.y = y
          letter.add(bar)
        }
        const diagonal = new Mesh(new BoxGeometry(0.18, 0.035, 0.035), material)
        diagonal.rotation.z = Math.PI / 4
        letter.add(diagonal)
        const gap = i === 2 ? 0.08 : 0
        letter.position.set(0.63 + i * 0.2 + gap, 0.85 + i * 0.2 + gap, 0.29)
        letter.userData.scale = 0.9 + i * 0.26
        group.add(letter)
      }
      break
    }
    case 'pause': {
      const material = plastic(0x64748b)
      const marker = new Group()
      for (const x of [-0.065, 0.065]) {
        const bar = new Mesh(new BoxGeometry(0.073, 0.27, 0.065), material)
        bar.position.x = x
        marker.add(bar)
      }
      marker.position.copy(MARKER_AT)
      group.add(marker)
      break
    }
    case 'alert': {
      // El «?» como silueta plana: el gancho se contornea a lo largo de la curva y se cierra
      // con medias lunas; el punto, aparte.
      const material = plastic(colors.body)
      const curve = new CatmullRomCurve3(
        [
          [-0.24, 0.23],
          [-0.18, 0.41],
          [0.03, 0.47],
          [0.22, 0.35],
          [0.23, 0.17],
          [0.08, 0.03],
          [0, -0.12],
        ].map(([x, y]) => new Vector3(x, y, 0)),
      )
      const radius = 0.075
      const samples = Array.from({ length: 49 }, (_, i) => {
        const point = curve.getPoint(i / 48)
        const tangent = curve.getTangent(i / 48)
        return { point, angle: Math.atan2(tangent.y, tangent.x) + Math.PI / 2 }
      })
      const edge = ({ point, angle }: (typeof samples)[number], side: number): [number, number] => [
        point.x + side * radius * Math.cos(angle),
        point.y + side * radius * Math.sin(angle),
      ]
      const hook = new Shape()
      hook.moveTo(...edge(samples[0], 1))
      for (const sample of samples.slice(1)) hook.lineTo(...edge(sample, 1))
      const end = samples[samples.length - 1]
      hook.absarc(end.point.x, end.point.y, radius, end.angle, end.angle - Math.PI, true)
      for (const sample of samples.slice(0, -1).reverse()) hook.lineTo(...edge(sample, -1))
      const start = samples[0]
      hook.absarc(
        start.point.x,
        start.point.y,
        radius,
        start.angle - Math.PI,
        start.angle - 2 * Math.PI,
        true,
      )
      hook.closePath()
      const marker = new Group()
      marker.add(
        flatSymbol(hook, 0.065, material),
        flatSymbol(new Shape().absarc(0, -0.34, 0.09, 0, Math.PI * 2, false), 0.065, material),
      )
      marker.position.copy(MARKER_AT)
      group.add(marker)
      break
    }
    case 'stress': {
      const material = new MeshBasicMaterial({ color: new Color(colors.body) })
      for (const side of [-1, 1])
        for (let i = 0; i < 2; i++) {
          const ray = new Mesh(new BoxGeometry(0.19, 0.045, 0.035), material)
          ray.position.set(side * (0.82 + i * 0.16), 0.73 - i * 0.26, 0.22)
          ray.rotation.z = side * (0.85 - i * 0.5)
          group.add(ray)
        }
      break
    }
    case 'confetti': {
      // Posiciones fijas (no aleatorias), para que las capturas salgan siempre iguales.
      const random = seeded(7)
      for (let i = 0; i < 14; i++) {
        const piece = new Mesh(
          new PlaneGeometry(0.08, 0.08),
          new MeshBasicMaterial({
            color: new Color(colors.confetti[i % colors.confetti.length]),
            side: DoubleSide,
            transparent: true,
            depthWrite: false,
          }),
        )
        piece.userData.target = new Vector3(
          (random() - 0.5) * 2.2,
          0.6 + random(),
          (random() - 0.5) * 0.6 + 0.2,
        )
        group.add(piece)
      }
      break
    }
  }
  return group
}

/** Libera geometrías y materiales del accesorio. */
export function disposeTree(root: Object3D) {
  const materials = new Set<Material>()
  root.traverse((o) => {
    if (!(o instanceof Mesh)) return
    o.geometry.dispose()
    ;(Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => materials.add(m))
  })
  materials.forEach((m) => m.dispose())
}

/** Números «aleatorios» repetibles (mulberry32). */
function seeded(seed: number) {
  let a = seed
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
