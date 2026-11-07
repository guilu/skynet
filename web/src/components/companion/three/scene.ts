// Skyny 3D (TD-A): el modelo del estudio `skyny-studio.html` portado a three de npm, para un
// único Skyny grande (el del login). Sin sombras de mapa ni controles de órbita: render ligero.
import {
  ACESFilmicToneMapping,
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
  PerspectiveCamera,
  PlaneGeometry,
  PMREMGenerator,
  PointLight,
  Scene,
  SphereGeometry,
  SRGBColorSpace,
  Vector2,
  Vector3,
  WebGLRenderer,
  type Material,
} from 'three'
import { BANDS, BAND_NEXUS, BODY_RADIUS, deform, surfaceAt, type Vec3 } from './body'
import { drawFace, FACE_PX, type FaceColors, type FaceEyes } from './face'

/** Colores de Skyny, leídos de los tokens del tema. */
export interface SkynyColors extends FaceColors {
  /** Cuerpo (`--primary` en Reposo). */
  body: string
  /** Bandas y carcasa de la cara (`--companion-band`). */
  band: string
  /** Nodos: cabeza y manos (`--companion-node`). */
  node: string
  /** Tema oscuro: la sombra del suelo pasa a ser un resplandor. */
  dark: boolean
}

/** Manos sobre la cara, como en el 2D: `cover` tapa los dos ojos y `peek` deja ver uno. */
export type Pose3D = 'cover' | 'peek' | undefined

/** Dónde están las manos en reposo (izquierda y derecha) y la cabeza. */
const HEAD: Vec3 = [0, 0.78, 0]
const HANDS: [Vec3, Vec3] = [
  [-0.78, -0.42, 0.12],
  [0.79, -0.42, 0.12],
]
/** Cuánto se mueve la mano derecha al mirar de reojo, desde donde tapa el ojo. */
const PEEK_SHIFT = new Vector3(0.12, -0.34, -0.06)
/** Lo que dura el parpadeo, y cada cuánto parpadea (al azar entre los dos). */
const BLINK_MS = 140
const BLINK_EVERY: [number, number] = [3200, 6200]

export class SkynyScene {
  private readonly renderer: WebGLRenderer
  private readonly scene = new Scene()
  private readonly camera = new PerspectiveCamera(34, 1, 0.1, 50)
  private readonly root = new Group()
  private readonly face: CanvasRenderingContext2D
  private readonly faceTexture: CanvasTexture
  private readonly body: MeshPhysicalMaterial
  private readonly band: MeshPhysicalMaterial
  private readonly node: MeshPhysicalMaterial
  private readonly glowLight: PointLight
  private readonly floor: Mesh<PlaneGeometry, MeshBasicMaterial>
  private readonly hands: Mesh[] = []
  private colors: SkynyColors
  private pose: Pose3D
  private poseAmount = 0
  private peekAmount = 0
  /** Hacia dónde mira, de -1 a 1 en cada eje, y por dónde va de camino. */
  private aim: [number, number] = [0, 0]
  private gaze: [number, number] = [0, 0]
  private drawn = ''
  private nextBlink = 0
  private blinkUntil = 0
  private frame = 0
  private readonly start = performance.now()

  constructor(canvas: HTMLCanvasElement, colors: SkynyColors) {
    this.colors = colors
    this.renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.toneMapping = ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1.05
    this.renderer.outputColorSpace = SRGBColorSpace

    this.camera.position.set(0, 0.12, 4.3)
    this.camera.lookAt(0, 0.1, 0)
    this.environment()
    this.lights()
    this.glowLight = new PointLight(colors.body, 2.2, 3.5)
    this.glowLight.position.set(0, -0.65, 0)
    this.scene.add(this.glowLight)

    const faceCanvas = document.createElement('canvas')
    faceCanvas.width = FACE_PX
    faceCanvas.height = FACE_PX
    this.face = faceCanvas.getContext('2d')!
    this.faceTexture = new CanvasTexture(faceCanvas)
    this.faceTexture.colorSpace = SRGBColorSpace

    this.body = new MeshPhysicalMaterial({
      roughness: 0.22,
      metalness: 0.03,
      clearcoat: 0.5,
      clearcoatRoughness: 0.15,
    })
    this.band = new MeshPhysicalMaterial({
      roughness: 0.55,
      metalness: 0.12,
      clearcoat: 0.25,
      clearcoatRoughness: 0.2,
      side: DoubleSide,
    })
    this.node = new MeshPhysicalMaterial({
      roughness: 0.1,
      metalness: 0.02,
      clearcoat: 0.95,
      clearcoatRoughness: 0.08,
    })
    this.build()
    this.floor = this.makeFloor()
    this.scene.add(this.root, this.floor)
    this.setColors(colors)
  }

  /** Cambia los colores (tema claro u oscuro, o paleta personalizada). */
  setColors(colors: SkynyColors) {
    this.colors = colors
    this.body.color = new Color(colors.body)
    this.band.color = new Color(colors.band)
    this.node.color = new Color(colors.node)
    this.glowLight.color = new Color(colors.body)
    this.floor.material.map?.dispose()
    this.floor.material.map = floorTexture(colors.dark ? colors.glow : '#0f172a', colors.dark)
    this.floor.material.needsUpdate = true
    this.drawn = ''
  }

  setPose(pose: Pose3D) {
    this.pose = pose
  }

  /** Hacia dónde mira: de -1 a 1 en horizontal y vertical, (0, 0) al frente. */
  lookAt(x: number, y: number) {
    this.aim = [clamp(x), clamp(y)]
  }

  resize(px: number) {
    this.renderer.setSize(px, px, false)
    this.camera.aspect = 1
    this.camera.updateProjectionMatrix()
  }

  /** Pinta un fotograma quieto, sin flotar ni parpadear (para movimiento reducido). */
  renderStill() {
    this.update(0, false)
    this.renderer.render(this.scene, this.camera)
  }

  /** Empieza a animarse; el navegador para el bucle solo con la pestaña oculta. */
  play() {
    if (this.frame) return
    const tick = (now: number) => {
      this.frame = requestAnimationFrame(tick)
      this.update(now, true)
      this.renderer.render(this.scene, this.camera)
    }
    this.frame = requestAnimationFrame(tick)
  }

  stop() {
    cancelAnimationFrame(this.frame)
    this.frame = 0
  }

  dispose() {
    this.stop()
    this.scene.traverse((object) => {
      if (!(object instanceof Mesh)) return
      object.geometry.dispose()
      const materials: Material[] = Array.isArray(object.material)
        ? object.material
        : [object.material]
      materials.forEach((m) => {
        if ('map' in m && m.map && typeof m.map === 'object' && 'dispose' in m.map)
          (m.map as { dispose: () => void }).dispose()
        m.dispose()
      })
    })
    this.scene.environment?.dispose()
    this.faceTexture.dispose()
    this.renderer.dispose()
  }

  private update(now: number, animated: boolean) {
    const t = (now - this.start) / 1000
    const ease = animated ? 0.16 : 1

    // Flotar y respirar (como el estudio, un poco más suave).
    const bob = animated ? Math.sin(t * 2) : 0
    this.root.position.y = 0.05 + bob * 0.035
    this.root.rotation.z = animated ? Math.sin(t * 1.6) * 0.018 : 0
    const breathe = 1 + bob * 0.01
    this.root.scale.set(1 / Math.sqrt(breathe), breathe, 1)
    const s = 1 - bob * 0.04
    this.floor.scale.set(s, s, 1)

    // Gira hacia lo que mira y mueve los ojos en la pantalla.
    this.gaze[0] += (this.aim[0] - this.gaze[0]) * ease
    this.gaze[1] += (this.aim[1] - this.gaze[1]) * ease
    this.root.rotation.y = this.gaze[0] * 0.2
    this.root.rotation.x = this.gaze[1] * 0.1

    // Manos: tapar los ojos, o mirar de reojo con la derecha a medias.
    const cover = this.pose ? 1 : 0
    const peek = this.pose === 'peek' ? 1 : 0
    this.poseAmount = approach(this.poseAmount, cover, ease)
    this.peekAmount = approach(this.peekAmount, peek, ease)
    this.hands.forEach((hand, i) => {
      const amount = this.poseAmount
      const forward = Math.min(1, amount / 0.45)
      const over = Math.max(0, (amount - 0.45) / 0.55)
      const side = i === 0 ? -1 : 1
      const [x, y, z] = HANDS[i]
      hand.position.set(x - side * 0.45 * over, y + 0.38 * over, z + 0.82 * forward)
      // De reojo, la mano derecha baja y se aparta un poco: deja ver ese ojo.
      if (i === 1) hand.position.add(PEEK_SHIFT.clone().multiplyScalar(this.peekAmount))
    })

    // Parpadeo cada pocos segundos.
    if (animated) {
      if (!this.nextBlink) this.nextBlink = now + between(BLINK_EVERY)
      if (now >= this.nextBlink) {
        this.blinkUntil = now + BLINK_MS
        this.nextBlink = now + between(BLINK_EVERY)
      }
    }
    const eyes: FaceEyes = now < this.blinkUntil ? 'bars' : 'pills'
    const gx = Math.round(this.gaze[0] * 30)
    const gy = Math.round(this.gaze[1] * 22)
    const key = `${eyes}:${gx}:${gy}`
    if (key !== this.drawn) {
      this.drawn = key
      drawFace(this.face, eyes, this.colors, [gx, gy])
      this.faceTexture.needsUpdate = true
    }
  }

  private environment() {
    // Un estudio de paneles blancos como entorno, para que el bisel metálico se lea.
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
    const pmrem = new PMREMGenerator(this.renderer)
    this.scene.environment = pmrem.fromScene(studio, 0.04).texture
    pmrem.dispose()
    studio.traverse((o) => {
      if (o instanceof Mesh) {
        o.geometry.dispose()
        o.material.dispose()
      }
    })
  }

  private lights() {
    this.scene.add(new AmbientLight(0xffffff, 0.85))
    const key = new DirectionalLight(0xffffff, 1.15)
    key.position.set(3, 5, 4)
    const rim = new DirectionalLight(0xbae6fd, 1.25)
    rim.position.set(-3, 3, -4)
    const fill = new DirectionalLight(0xffffff, 0.4)
    fill.position.set(-4, 1, 2)
    this.scene.add(key, rim, fill)
  }

  private build() {
    const shell = new Mesh(bodyGeometry(), this.body)
    this.root.add(shell)

    for (const path of BANDS) this.root.add(new Mesh(bandGeometry(path, 0.2, 0.012), this.band))
    const nexus = new Mesh(new CylinderGeometry(0.12, 0.13, 0.06, 24), this.band)
    nexus.rotation.x = Math.PI / 2
    nexus.position.set(BAND_NEXUS[0], BAND_NEXUS[1], BAND_NEXUS[2] - 0.02)
    this.root.add(nexus)

    // Cara: carcasa oscura, bisel metálico y pantalla OLED.
    const face = new Group()
    face.position.set(0, -0.04, 0.5)
    face.rotation.x = -0.14
    const housing = new Mesh(
      new CylinderGeometry(0.438, 0.32, 0.3, 96).rotateX(Math.PI / 2),
      this.band,
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
      new MeshPhysicalMaterial({
        color: 0xdce3ed,
        roughness: 0.26,
        metalness: 0.78,
        clearcoat: 0.95,
        clearcoatRoughness: 0.06,
      }),
    )
    bezel.rotation.x = Math.PI / 2
    bezel.position.z = 0.02
    const backing = new Mesh(
      new CircleGeometry(0.39, 64),
      new MeshBasicMaterial({ color: 0x050913 }),
    )
    backing.position.z = -0.01
    const screen = new Mesh(
      new CircleGeometry(0.38, 64),
      new MeshBasicMaterial({ map: this.faceTexture, toneMapped: false }),
    )
    screen.position.z = 0.01
    face.add(housing, bezel, backing, screen)
    this.root.add(face)

    // Nodos: la cabeza y las dos manos.
    const sphere = new SphereGeometry(0.33, 40, 40)
    const head = new Mesh(sphere, this.node)
    head.position.set(...HEAD)
    this.root.add(head)
    HANDS.forEach((at) => {
      const hand = new Mesh(sphere, this.node)
      hand.position.set(...at)
      this.hands.push(hand)
      this.root.add(hand)
    })
  }

  private makeFloor() {
    const floor = new Mesh(
      new PlaneGeometry(2.4, 1.3),
      new MeshBasicMaterial({ transparent: true, depthWrite: false }),
    )
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -0.92
    return floor
  }
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

/** La sombra del suelo (o, en oscuro, un resplandor suave del color de los ojos). */
function floorTexture(color: string, dark: boolean) {
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

const clamp = (n: number) => Math.max(-1, Math.min(1, n))
const approach = (from: number, to: number, ease: number) => {
  const next = from + (to - from) * ease
  return Math.abs(to - next) < 0.001 ? to : next
}
const between = ([a, b]: [number, number]) => a + Math.random() * (b - a)
