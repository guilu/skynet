// El modelo de Skyny 3D (secciones 4 a 8 del estudio): entorno, luces, cuerpo, bandas, cara con
// pantalla OLED, nodos y sombra del suelo. Sin animación: eso va en `scene.ts`.
import {
  AmbientLight,
  BufferGeometry,
  CanvasTexture,
  CatmullRomCurve3,
  CircleGeometry,
  Color,
  CylinderGeometry,
  DirectionalLight,
  DoubleSide,
  ExtrudeGeometry,
  Float32BufferAttribute,
  Group,
  Mesh,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  Path,
  PlaneGeometry,
  PMREMGenerator,
  Scene,
  Shape,
  SphereGeometry,
  SRGBColorSpace,
  Vector2,
  Vector3,
  type WebGLRenderer,
} from 'three'
import { BANDS, BODY_RADIUS, deform, rearShellZ, surfaceAt, type Vec3 } from './body'

/** La cabeza (oculta desde el estudio v2; de ahí sale el confeti) y las manos, en reposo. */
export const HEAD: Vec3 = [0, 0.9, 0]
export const HANDS: [Vec3, Vec3] = [
  [-0.88, -0.42, 0.12],
  [0.88, -0.42, 0.12],
]
/** Altura del suelo y del cuerpo en reposo. */
export const FLOOR_Y = -0.92
export const REST_Y = 0.05

/** Las piezas del modelo que se animan o cambian de color. */
export interface SkynyModel {
  root: Group
  head: Mesh
  hands: [Mesh, Mesh]
  /** La pantalla, para saber dónde cae la cara en el lienzo (la mirada que sigue al puntero). */
  screen: Mesh
  floor: Mesh<PlaneGeometry, MeshBasicMaterial>
  materials: {
    body: MeshPhysicalMaterial
    band: MeshPhysicalMaterial
    /** Carcasa de la cara y hebilla: el acabado de las bandas, algo más oscuro y satinado. */
    hardware: MeshPhysicalMaterial
    node: MeshPhysicalMaterial
    bezel: MeshPhysicalMaterial
  }
}

/** Estudio de paneles blancos como entorno, para que el bisel metálico se lea. */
export function studioEnvironment(scene: Scene, renderer: WebGLRenderer) {
  const studio = new Scene()
  studio.background = new Color(0xcbd4e4)
  for (const [x, y, z, w, h] of [
    [-3, 2, 2, 3, 5],
    [3, 1, 1, 2, 4],
    [0, 4, 0, 5, 3],
  ]) {
    const panel = new Mesh(new PlaneGeometry(w, h), new MeshBasicMaterial({ color: 0xffffff }))
    panel.position.set(x, y, z)
    panel.lookAt(0, 0, 0)
    studio.add(panel)
  }
  const pmrem = new PMREMGenerator(renderer)
  scene.environment = pmrem.fromScene(studio, 0.04).texture
  pmrem.dispose()
  studio.traverse((o) => {
    if (o instanceof Mesh) {
      o.geometry.dispose()
      o.material.dispose()
    }
  })
}

/** Luz de producto: ambiente, principal, contraluz azulado y relleno. */
export function studioLights(scene: Scene) {
  scene.add(new AmbientLight(0xffffff, 0.85))
  const key = new DirectionalLight(0xffffff, 1.15)
  key.position.set(3, 5, 4)
  const rim = new DirectionalLight(0xbae6fd, 1.25)
  rim.position.set(-3, 3, -4)
  const fill = new DirectionalLight(0xffffff, 0.4)
  fill.position.set(-4, 1, 2)
  scene.add(key, rim, fill)
}

/** Monta a Skyny; `screen` es la textura de la pantalla donde se pintan los ojos. */
export function buildSkyny(screen: CanvasTexture): SkynyModel {
  // Acabados del estudio v2: plástico mate con poco barniz y metal plano en el bisel.
  const band = new MeshPhysicalMaterial({
    roughness: 0.78,
    metalness: 0,
    clearcoat: 0,
    clearcoatRoughness: 0.5,
    side: DoubleSide,
  })
  const materials = {
    body: new MeshPhysicalMaterial({
      roughness: 0.38,
      metalness: 0,
      clearcoat: 0.22,
      clearcoatRoughness: 0.3,
    }),
    band,
    hardware: new MeshPhysicalMaterial({
      roughness: 0.42,
      metalness: 0.08,
      clearcoat: 0.18,
      clearcoatRoughness: 0.32,
      side: DoubleSide,
    }),
    node: new MeshPhysicalMaterial({
      roughness: 0.38,
      metalness: 0,
      clearcoat: 0.2,
      clearcoatRoughness: 0.3,
    }),
    bezel: new MeshPhysicalMaterial({
      color: 0xc7cbd1,
      roughness: 0.32,
      metalness: 1,
      clearcoat: 0,
      side: DoubleSide,
    }),
  }
  const root = new Group()
  root.position.y = REST_Y

  // Cuerpo y bandas, que acaban en la hebilla triangular de la espalda.
  root.add(new Mesh(bodyGeometry(), materials.body))
  for (const path of BANDS) root.add(new Mesh(bandGeometry(path, 0.2, 0.012), materials.band))
  root.add(new Mesh(buckleGeometry(), materials.hardware))

  // Cara: carcasa oscura, bisel metálico plano y pantalla OLED.
  const face = new Group()
  face.position.set(0, -0.04, 0.5)
  face.rotation.x = -0.14
  const housing = new Mesh(
    new CylinderGeometry(0.438, 0.32, 0.3, 96).rotateX(Math.PI / 2),
    materials.hardware,
  )
  housing.position.z = -0.17
  const ring = new Shape().absarc(0, 0, 0.44, 0, Math.PI * 2, false)
  ring.holes.push(new Path().absarc(0, 0, 0.375, 0, Math.PI * 2, true))
  const bezel = new Mesh(
    new ExtrudeGeometry(ring, { depth: 0.042, bevelEnabled: false, curveSegments: 64 }).translate(
      0,
      0,
      -0.024,
    ),
    materials.bezel,
  )
  bezel.position.z = 0.02
  const backing = new Mesh(new CircleGeometry(0.39, 64), new MeshBasicMaterial({ color: 0x050913 }))
  backing.position.z = -0.01
  const display = new Mesh(
    new CircleGeometry(0.38, 64),
    new MeshBasicMaterial({ map: screen, toneMapped: false }),
  )
  display.position.z = 0.01
  face.add(housing, bezel, backing, display)
  root.add(face)

  // Nodos: las dos manos. La cabeza sigue ahí (de ella sale el confeti), pero no se ve.
  const sphere = new SphereGeometry(0.33, 40, 40)
  const head = new Mesh(sphere, materials.node)
  head.position.set(...HEAD)
  head.visible = false
  const hand = (at: Vec3) => {
    const mesh = new Mesh(sphere, materials.node)
    mesh.position.set(...at)
    return mesh
  }
  const hands: [Mesh, Mesh] = [hand(HANDS[0]), hand(HANDS[1])]
  root.add(head, ...hands)

  const floor = new Mesh(
    new PlaneGeometry(2.4, 1.3),
    new MeshBasicMaterial({ transparent: true, depthWrite: false }),
  )
  floor.rotation.x = -Math.PI / 2
  floor.position.y = FLOOR_Y

  return { root, head, hands, screen: display, floor, materials }
}

/** La sombra del suelo (o, en oscuro, un resplandor suave del color de los ojos). */
export function floorTexture(color: string, dark: boolean) {
  const c = document.createElement('canvas')
  c.width = 256
  c.height = 256
  const ctx = c.getContext('2d')!
  // Se apaga del todo antes del borde del plano, para que no se vea su contorno: primero la
  // forma (solo alfa) y luego se tiñe del color.
  const g = ctx.createRadialGradient(128, 128, 0, 128, 128, 124)
  g.addColorStop(0, 'rgba(0, 0, 0, 1)')
  g.addColorStop(0.45, 'rgba(0, 0, 0, 0.75)')
  g.addColorStop(1, 'rgba(0, 0, 0, 0)')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, 256, 256)
  ctx.globalCompositeOperation = 'source-in'
  ctx.globalAlpha = dark ? 0.3 : 0.32
  ctx.fillStyle = color
  ctx.fillRect(0, 0, 256, 256)
  const texture = new CanvasTexture(c)
  texture.colorSpace = SRGBColorSpace
  return texture
}

/** La esfera deformada en cuerpo, con las normales de la costura UV unidas. */
function bodyGeometry() {
  const geometry = new SphereGeometry(BODY_RADIUS, 72, 72)
  const pos = geometry.attributes.position
  for (let i = 0; i < pos.count; i++) {
    const [x, y, z] = deform([pos.getX(i), pos.getY(i), pos.getZ(i)])
    pos.setXYZ(i, x, y, z)
  }
  geometry.computeVertexNormals()
  const normals = geometry.attributes.normal
  const groups = new Map<string, number[]>()
  for (let i = 0; i < pos.count; i++) {
    const key = [pos.getX(i), pos.getY(i), pos.getZ(i)].map((n) => Math.round(n * 1e6)).join()
    groups.set(key, [...(groups.get(key) ?? []), i])
  }
  const n = new Vector3()
  for (const ids of groups.values()) {
    n.set(0, 0, 0)
    for (const i of ids) n.add(new Vector3().fromBufferAttribute(normals, i))
    n.normalize()
    for (const i of ids) normals.setXYZ(i, n.x, n.y, n.z)
  }
  return geometry
}

/** Una banda ceñida al cuerpo a lo largo de `path`. */
function bandGeometry(path: Vec3[], width: number, clearance: number) {
  const curve = new CatmullRomCurve3(
    path.map((p) => new Vector3(...p)),
    false,
    'centripetal',
  )
  const segments = 96
  const across = 24
  const vertices: number[] = []
  const indices: number[] = []
  const point = new Vector3()
  const center = new Vector3()
  const side = new Vector3()
  for (let i = 0; i <= segments; i++) {
    const u = i / segments
    curve.getPoint(u, point)
    side.crossVectors(curve.getTangent(u), point.clone().normalize()).normalize()
    // El ancho se mide sobre la cáscara, no sobre la curva guía (que va a distinta profundidad).
    center.set(...surfaceAt([point.x, point.y, point.z], clearance))
    for (let j = 0; j <= across; j++) {
      const p = center.clone().addScaledVector(side, (j / across - 0.5) * width)
      vertices.push(...(j === across / 2 ? center.toArray() : surfaceAt(p.toArray(), clearance)))
      if (i < segments && j < across) {
        const k = i * (across + 1) + j
        const next = k + across + 1
        indices.push(k, k + 1, next, k + 1, next + 1, next)
      }
    }
  }
  const geometry = new BufferGeometry()
  geometry.setAttribute('position', new Float32BufferAttribute(vertices, 3))
  geometry.setIndex(indices)
  geometry.computeVertexNormals()
  return geometry
}

/** Esquinas redondeadas de un triángulo, recortando `trim` de cada lado (la del estudio v2). */
function roundedTriangle<T extends Path>(path: T, corners: Vector2[], trim: number): T {
  const toward = (from: Vector2, to: Vector2) => from.clone().lerp(to, trim / from.distanceTo(to))
  const start = toward(corners[0], corners[2])
  path.moveTo(start.x, start.y)
  corners.forEach((corner, i) => {
    const incoming = toward(corner, corners[(i + 2) % 3])
    const outgoing = toward(corner, corners[(i + 1) % 3])
    if (i > 0) path.lineTo(incoming.x, incoming.y)
    path.quadraticCurveTo(corner.x, corner.y, outgoing.x, outgoing.y)
  })
  path.closePath()
  return path
}

/**
 * La hebilla de la espalda: un marco triangular abierto, extruido y doblado sobre la cáscara.
 * Las tapas se subdividen antes de doblarlas para que no salgan facetas.
 */
function buckleGeometry() {
  const outline = roundedTriangle(
    new Shape(),
    [new Vector2(0, 0.24), new Vector2(-0.225, -0.163), new Vector2(0.225, -0.163)],
    0.058,
  )
  outline.holes.push(
    roundedTriangle(
      new Path(),
      [new Vector2(0, 0.142), new Vector2(0.136, -0.103), new Vector2(-0.136, -0.103)],
      0.028,
    ),
  )
  const flat = new ExtrudeGeometry(outline, {
    depth: 0.038,
    bevelEnabled: true,
    bevelSize: 0.009,
    bevelThickness: 0.009,
    bevelSegments: 4,
    curveSegments: 12,
    steps: 1,
  })
  let points = Array.from(flat.attributes.position.array)
  flat.dispose()
  for (let pass = 0; pass < 2; pass++) {
    const next: number[] = []
    const mid = (a: number[], b: number[]) => a.map((v, i) => (v + b[i]) / 2)
    for (let i = 0; i < points.length; i += 9) {
      const a = points.slice(i, i + 3)
      const b = points.slice(i + 3, i + 6)
      const c = points.slice(i + 6, i + 9)
      const ab = mid(a, b)
      const bc = mid(b, c)
      const ca = mid(c, a)
      next.push(...a, ...ab, ...ca, ...ab, ...b, ...bc, ...ca, ...bc, ...c, ...ab, ...bc, ...ca)
    }
    points = next
  }
  // Cada vértice se apoya en la espalda, a la profundidad de la cáscara en su (x, y).
  const shell = new Map<string, number>()
  const weld = new Map<string, number>()
  const vertices: number[] = []
  const indices: number[] = []
  for (let i = 0; i < points.length; i += 3) {
    const [x, y, depth] = points.slice(i, i + 3)
    const xy = `${Math.round(x * 1e6)},${Math.round(y * 1e6)}`
    if (!shell.has(xy)) shell.set(xy, rearShellZ(x, y))
    const key = `${xy},${Math.round(depth * 1e6)}`
    if (!weld.has(key)) {
      weld.set(key, vertices.length / 3)
      vertices.push(x, y, shell.get(xy)! - 0.032 + depth)
    }
    indices.push(weld.get(key)!)
  }
  const geometry = new BufferGeometry()
  geometry.setAttribute('position', new Float32BufferAttribute(vertices, 3))
  geometry.setIndex(indices)
  geometry.computeVertexNormals()
  return geometry
}
