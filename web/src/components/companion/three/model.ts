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
  Float32BufferAttribute,
  Group,
  LatheGeometry,
  Mesh,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  PlaneGeometry,
  PMREMGenerator,
  Scene,
  SphereGeometry,
  SRGBColorSpace,
  Vector2,
  Vector3,
  type WebGLRenderer,
} from 'three'
import { BANDS, BAND_NEXUS, BODY_RADIUS, deform, surfaceAt, type Vec3 } from './body'

/** La cabeza y las manos (izquierda y derecha), en reposo. */
export const HEAD: Vec3 = [0, 0.78, 0]
export const HANDS: [Vec3, Vec3] = [
  [-0.78, -0.42, 0.12],
  [0.78, -0.42, 0.12],
]
/** Altura del suelo y del cuerpo en reposo. */
export const FLOOR_Y = -0.92
export const REST_Y = 0.05

/** Las piezas del modelo que se animan o cambian de color. */
export interface SkynyModel {
  root: Group
  head: Mesh
  hands: [Mesh, Mesh]
  floor: Mesh<PlaneGeometry, MeshBasicMaterial>
  materials: {
    body: MeshPhysicalMaterial
    band: MeshPhysicalMaterial
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
  const materials = {
    body: new MeshPhysicalMaterial({
      roughness: 0.22,
      metalness: 0.03,
      clearcoat: 0.5,
      clearcoatRoughness: 0.15,
    }),
    band: new MeshPhysicalMaterial({
      roughness: 0.55,
      metalness: 0.12,
      clearcoat: 0.25,
      clearcoatRoughness: 0.2,
      side: DoubleSide,
    }),
    node: new MeshPhysicalMaterial({
      roughness: 0.1,
      metalness: 0.02,
      clearcoat: 0.95,
      clearcoatRoughness: 0.08,
    }),
    bezel: new MeshPhysicalMaterial({
      color: 0xdce3ed,
      roughness: 0.26,
      metalness: 0.78,
      clearcoat: 0.95,
      clearcoatRoughness: 0.06,
    }),
  }
  const root = new Group()
  root.position.y = REST_Y

  // Cuerpo y bandas, que se juntan en una Y en la espalda.
  root.add(new Mesh(bodyGeometry(), materials.body))
  for (const path of BANDS) root.add(new Mesh(bandGeometry(path, 0.2, 0.012), materials.band))
  const nexus = new Mesh(new CylinderGeometry(0.12, 0.13, 0.06, 24), materials.band)
  nexus.rotation.x = Math.PI / 2
  nexus.position.set(BAND_NEXUS[0], BAND_NEXUS[1], BAND_NEXUS[2] - 0.02)
  root.add(nexus)

  // Cara: carcasa oscura, bisel metálico y pantalla OLED.
  const face = new Group()
  face.position.set(0, -0.04, 0.5)
  face.rotation.x = -0.14
  const housing = new Mesh(
    new CylinderGeometry(0.438, 0.32, 0.3, 96).rotateX(Math.PI / 2),
    materials.band,
  )
  housing.position.z = -0.17
  const bezel = new Mesh(
    new LatheGeometry(
      [
        [0.375, -0.024],
        [0.375, 0.01],
        [0.383, 0.026],
        [0.42, 0.03],
        [0.438, 0.016],
        [0.44, -0.022],
        [0.375, -0.024],
      ].map(([x, y]) => new Vector2(x, y)),
      96,
    ),
    materials.bezel,
  )
  bezel.rotation.x = Math.PI / 2
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

  // Nodos: la cabeza y las dos manos.
  const sphere = new SphereGeometry(0.33, 40, 40)
  const head = new Mesh(sphere, materials.node)
  head.position.set(...HEAD)
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

  return { root, head, hands, floor, materials }
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
  const side = new Vector3()
  for (let i = 0; i <= segments; i++) {
    const u = i / segments
    curve.getPoint(u, point)
    side.crossVectors(curve.getTangent(u), point.clone().normalize()).normalize()
    for (let j = 0; j <= across; j++) {
      const p = point.clone().addScaledVector(side, (j / across - 0.5) * width)
      vertices.push(...surfaceAt([p.x, p.y, p.z], clearance))
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
