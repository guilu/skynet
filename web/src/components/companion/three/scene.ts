// Skyny 3D en marcha: el modelo (`model.ts`), sus expresiones (`states.ts`) y la animación de las
// secciones 12 y 13 del estudio (gesto de entrada de cada estado, flotar, confeti, parpadeo),
// más las poses y la mirada del login. Lo usan el login y el estudio (`skyny-studio.html`).
import {
  ACESFilmicToneMapping,
  CanvasTexture,
  Color,
  Group,
  Mesh,
  MeshBasicMaterial,
  PerspectiveCamera,
  PointLight,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
  type Material,
} from 'three'
import type { CompanionState } from '../state'
import { buildAccessory, disposeTree, GRIP_AT, MARKER_AT, SPEED_X } from './accessories'
import { drawFace, FACE_PX, type FaceEyes } from './face'
import {
  buildSkyny,
  floorTexture,
  HANDS,
  REST_Y,
  studioEnvironment,
  studioLights,
  type SkynyModel,
} from './model'
import {
  ENTRY_MS,
  entryGesture,
  errorShake,
  executionBurst,
  executionTravel,
  popIn,
  reveal,
  successHop,
} from './motion'
import { EXPRESSIONS_3D } from './states'
import type { SkynyColors } from './tokens'

/** Manos sobre la cara, como en el 2D: `cover` tapa los dos ojos y `peek` deja ver uno. */
export type Pose3D = 'cover' | 'peek' | undefined

/** Cuánto se mueve la mano derecha al mirar de reojo, desde donde tapa el ojo. */
const PEEK_SHIFT = new Vector3(0.12, -0.34, -0.06)
/** Lo que dura el parpadeo, y cada cuánto parpadea (al azar entre los dos). */
const BLINK_MS = 140
const BLINK_EVERY: [number, number] = [3200, 6200]
/** Ojos que pueden parpadear (los abiertos). */
const BLINKS: FaceEyes[] = ['pills', 'happy', 'wink', 'circles']
/** Cuánto tarda en cambiar los ojos de Error (antes se ven «sorprendidos»). */
const ERROR_EYES_MS = 180
/** Oscuro hacia el que se mezclan las bandas: cada estado lleva las suyas a juego. */
const BAND_SHADE = new Color('#05070f')

export interface SceneOptions {
  /** Hace los gestos de entrada al cambiar de estado (no con movimiento reducido). */
  gestures?: boolean
}

export class SkynyScene {
  readonly renderer: WebGLRenderer
  readonly camera = new PerspectiveCamera(34, 1, 0.1, 50)
  private readonly scene = new Scene()
  private readonly model: SkynyModel
  private readonly face: CanvasRenderingContext2D
  private readonly faceTexture: CanvasTexture
  private readonly glowLight: PointLight
  private accessory = new Group()
  private colors: SkynyColors
  private state: CompanionState = 'calm'
  private readonly gestures: boolean
  /** Cuándo empezó el gesto de entrada del estado, o null si no hay (o ya acabó). */
  private gestureAt: number | null = null
  private errorEyes = false
  private pose: Pose3D
  private poseAmount = 0
  private peekAmount = 0
  /** Hacia dónde mira, de -1 a 1 en cada eje, y por dónde va de camino. */
  private aim: [number, number] = [0, 0]
  private gaze: [number, number] = [0, 0]
  private idle = true
  private idleAmount = 1
  private drawn = ''
  private nextBlink = 0
  private blinkUntil = 0
  private frame = 0
  private readonly start = performance.now()

  constructor(canvas: HTMLCanvasElement, colors: SkynyColors, options: SceneOptions = {}) {
    this.colors = colors
    this.gestures = options.gestures ?? true
    this.renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.toneMapping = ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1.05
    this.renderer.outputColorSpace = SRGBColorSpace

    this.camera.position.set(0, 0.12, 4.3)
    this.camera.lookAt(0, 0.1, 0)
    studioEnvironment(this.scene, this.renderer)
    studioLights(this.scene)
    this.glowLight = new PointLight(0xffffff, 2.2, 3.5)
    this.glowLight.position.set(0, -0.65, 0)
    this.scene.add(this.glowLight)

    const faceCanvas = document.createElement('canvas')
    faceCanvas.width = FACE_PX
    faceCanvas.height = FACE_PX
    this.face = faceCanvas.getContext('2d')!
    this.faceTexture = new CanvasTexture(faceCanvas)
    this.faceTexture.colorSpace = SRGBColorSpace

    this.model = buildSkyny(this.faceTexture)
    this.model.root.add(this.accessory)
    this.scene.add(this.model.root, this.model.floor)
    this.setColors(colors)
  }

  /** Cambia los colores (tema claro u oscuro, o paleta personalizada). */
  setColors(colors: SkynyColors) {
    this.colors = colors
    const floor = this.model.floor.material
    floor.map?.dispose()
    floor.map = floorTexture(colors.dark ? colors.glow : '#0f172a', colors.dark)
    floor.needsUpdate = true
    this.paint()
  }

  /** Cambia de expresión; con gestos, hace el de entrada del nuevo estado. */
  setState(state: CompanionState) {
    this.state = state
    this.gestureAt = this.gestures && EXPRESSIONS_3D[state].entry ? performance.now() : null
    this.errorEyes = state === 'error' && this.gestureAt !== null
    this.paint()
  }

  get current() {
    return this.state
  }

  setPose(pose: Pose3D) {
    this.pose = pose
  }

  /** Hacia dónde mira: de -1 a 1 en horizontal y vertical, (0, 0) al frente. */
  lookAt(x: number, y: number) {
    this.aim = [clamp(x), clamp(y)]
  }

  /** Flotar y respirar en reposo (el botón «Idle» del estudio). */
  setIdle(on: boolean) {
    this.idle = on
  }

  /** Muestra la malla (el botón «Malla» del estudio). */
  setWireframe(on: boolean) {
    this.model.root.traverse((o) => {
      if (o instanceof Mesh && 'wireframe' in o.material) o.material.wireframe = on
    })
  }

  /** Parpadea ya. */
  blink() {
    this.blinkUntil = performance.now() + BLINK_MS
  }

  resize(width: number, height = width) {
    this.renderer.setSize(width, height, false)
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
  }

  /** Pinta un fotograma quieto, sin flotar ni parpadear (para movimiento reducido). */
  renderStill() {
    this.update(performance.now(), false)
    this.renderer.render(this.scene, this.camera)
  }

  /** El fotograma actual como PNG (la «Captura PNG» del estudio). */
  snapshot() {
    this.renderer.render(this.scene, this.camera)
    return this.renderer.domElement.toDataURL('image/png')
  }

  /** Empieza a animarse; `beforeRender` va justo antes de pintar cada fotograma. */
  play(beforeRender?: () => void) {
    if (this.frame) return
    const tick = (now: number) => {
      this.frame = requestAnimationFrame(tick)
      this.update(now, true)
      beforeRender?.()
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
    const materials = new Set<Material>()
    this.scene.traverse((object) => {
      if (!(object instanceof Mesh)) return
      object.geometry.dispose()
      const list: Material[] = Array.isArray(object.material) ? object.material : [object.material]
      list.forEach((m) => materials.add(m))
    })
    materials.forEach((m) => {
      if (m instanceof MeshBasicMaterial) m.map?.dispose()
      m.dispose()
    })
    this.scene.environment?.dispose()
    this.faceTexture.dispose()
    this.renderer.dispose()
  }

  /** Colores del estado actual y su accesorio. */
  private paint() {
    const body = this.colors.body[this.state]
    const { materials } = this.model
    materials.body.color = new Color(body)
    materials.band.color = new Color(body).lerp(BAND_SHADE, 0.72)
    materials.node.color = new Color(this.colors.node)
    this.glowLight.color = new Color(body)

    this.model.root.remove(this.accessory)
    disposeTree(this.accessory)
    this.accessory = buildAccessory(EXPRESSIONS_3D[this.state].accessory, {
      body,
      confetti: this.colors.confetti,
    })
    this.model.root.add(this.accessory)
    this.drawn = ''
  }

  private update(now: number, animated: boolean) {
    const t = (now - this.start) / 1000
    const ease = animated ? 0.16 : 1
    const { root, hands, head, floor } = this.model
    const state = this.state
    const expression = EXPRESSIONS_3D[state]

    // Gesto de entrada (sección 13 del estudio). Acabado, `elapsed` es infinito: todo en su sitio.
    let amount = 0
    if (this.gestureAt !== null) {
      const ms = now - this.gestureAt
      amount = entryGesture(ms)
      if (ms >= ENTRY_MS) this.gestureAt = null
    }
    const elapsed = this.gestureAt === null ? Infinity : now - this.gestureAt
    const pieces = this.accessory.children
    const thinking = state === 'thinking' ? amount : 0
    const reviewing = state === 'reviewing' ? amount : 0
    const running = state === 'running'
    const dash = elapsed - 260
    const thrust = running ? executionBurst(dash) : 0

    const grow = (piece: (typeof pieces)[number] | undefined, k: number, scale = 1) => {
      if (!piece) return
      piece.visible = k > 0
      piece.scale.setScalar(Math.max(k, 1e-4) * scale)
    }
    switch (state) {
      case 'offline':
        grow(pieces[0], reveal(elapsed - 240, 0), 1.25)
        break
      case 'sleeping':
        pieces.forEach((letter, i) =>
          grow(letter, reveal(elapsed - 200, i), letter.userData.scale as number),
        )
        break
      case 'paused':
        grow(pieces[0], reveal(elapsed - 180, 0))
        break
      case 'error':
        pieces.forEach((ray, i) => grow(ray, reveal(elapsed, i * 0.3)))
        if (this.errorEyes && elapsed >= ERROR_EYES_MS) this.errorEyes = false
        break
      case 'running':
        pieces.forEach((bar, i) => {
          const k = reveal(dash, i)
          bar.visible = k > 0
          bar.scale.x = Math.max(k, 1e-4) * (1 + 0.45 * executionBurst(dash - i * 85))
          bar.position.x = SPEED_X - i * 0.1 - 0.2 * thrust * k
        })
        break
      case 'thinking':
        pieces.forEach((bubble, i) => grow(bubble, reveal(elapsed, i)))
        break
      case 'waiting': {
        const marker = pieces[0]
        const u = (elapsed - 420) / 320
        grow(marker, u > 0 ? 1 : 0, 0.52 * popIn(elapsed - 420))
        if (marker) {
          const sway = Math.max(0, Math.min(1, (elapsed - 420) / 900))
          const s = Math.sin(sway * 4 * Math.PI) * (1 - sway)
          marker.position.x = MARKER_AT.x + 0.035 * s
          marker.rotation.z = -0.12 * s
        }
        break
      }
    }

    // Cuerpo: carrerilla o sacudida, inclinaciones del gesto y giro hacia donde mira.
    root.position.x = running
      ? executionTravel(elapsed)
      : state === 'error'
        ? errorShake(elapsed)
        : 0
    this.gaze[0] += (this.aim[0] - this.gaze[0]) * ease
    this.gaze[1] += (this.aim[1] - this.gaze[1]) * ease
    const tilt =
      thrust * 0.19 -
      thinking * 0.1 +
      (state === 'sleeping'
        ? entryGesture(elapsed) * 0.11
        : state === 'offline'
          ? executionBurst(elapsed) * 0.08
          : 0)
    root.rotation.x += (tilt + this.gaze[1] * 0.1 - root.rotation.x) * (animated ? 0.18 : 1)
    root.rotation.y +=
      (reviewing * 0.14 + this.gaze[0] * 0.2 - root.rotation.y) * (animated ? 0.18 : 1)

    // Manos: la derecha agarra la lupa o pide ayuda; luego, las poses del login.
    const [left, right] = hands
    left.position.set(...HANDS[0])
    right.position.set(
      0.79 - (1 - reviewing) * 0.01,
      -0.42 + reviewing * 0.04,
      0.12 + reviewing * 0.27,
    )
    if (state === 'waiting') {
      right.position.x += 0.08 * entryGesture(elapsed)
      right.position.y += 0.25 * entryGesture(elapsed)
    }
    const grip = this.accessory.getObjectByName('grip')
    if (grip) {
      grip.position.set(
        GRIP_AT.x - reviewing * 0.34,
        GRIP_AT.y + reviewing * 0.04,
        GRIP_AT.z + reviewing * 0.27,
      )
      grip.rotation.z = reviewing * ((3 * Math.PI) / 4)
    }
    this.poseAmount = approach(this.poseAmount, this.pose ? 1 : 0, ease)
    this.peekAmount = approach(this.peekAmount, this.pose === 'peek' ? 1 : 0, ease)
    if (this.poseAmount > 0) {
      const forward = Math.min(1, this.poseAmount / 0.45)
      const over = Math.max(0, (this.poseAmount - 0.45) / 0.55)
      hands.forEach((hand, i) => {
        const [x, y, z] = HANDS[i]
        const side = i === 0 ? -1 : 1
        hand.position.set(x - side * 0.45 * over, y + 0.38 * over, z + 0.82 * forward)
      })
      // De reojo, la mano derecha baja y se aparta un poco: deja ver ese ojo.
      right.position.addScaledVector(PEEK_SHIFT, this.peekAmount)
    }

    // Flotar y respirar; en pausa se queda quieto.
    this.idleAmount += ((state === 'paused' || !this.idle ? 0 : 1) - this.idleAmount) * 0.09
    if (this.idleAmount < 0.001) this.idleAmount = 0
    const bob = animated ? Math.sin(t * 2) * this.idleAmount : 0
    root.position.y = REST_Y + bob * 0.04
    root.rotation.z = animated ? Math.sin(t * 1.6) * 0.02 * this.idleAmount : 0
    const breathe = 1 + bob * 0.012
    root.scale.set(1 / Math.sqrt(breathe), breathe, 1)
    const s = 1 - bob * 0.04
    floor.scale.set(s, s, 1)

    // Éxito: salto y confeti que sale de la cabeza.
    if (state === 'success') {
      root.position.y += successHop(elapsed)
      pieces.forEach((piece, i) => {
        const u = Math.max(0, Math.min(1, (elapsed - 110 - (i % 4) * 35) / 1080))
        const spread = 1 - (1 - u) ** 3
        piece.visible = u > 0 && u < 1
        piece.position
          .set(head.position.x, head.position.y + 0.2, head.position.z + 0.26)
          .lerp(piece.userData.target as Vector3, spread)
        piece.position.y -= 1.05 * u * u
        if (piece instanceof Mesh) piece.material.opacity = Math.min(1, (1 - u) / 0.35)
        piece.rotation.z = (i % 2 ? -1 : 1) * u * (3 + (i % 5) * 0.5)
      })
    }

    // Ojos: los del estado, el parpadeo y hacia dónde miran.
    if (animated && BLINKS.includes(expression.eyes)) {
      if (!this.nextBlink) this.nextBlink = now + between(BLINK_EVERY)
      if (now >= this.nextBlink) {
        this.blinkUntil = now + BLINK_MS
        this.nextBlink = now + between(BLINK_EVERY)
      }
    }
    const eyes: FaceEyes =
      now < this.blinkUntil ? 'bars' : this.errorEyes ? 'error-pills' : expression.eyes
    const shift = reviewing * 12 - thinking * 16
    const gx = Math.round(shift + this.gaze[0] * 30)
    const gy = Math.round((state === 'thinking' ? -Math.abs(shift) : 0) + this.gaze[1] * 22)
    const key = `${eyes}:${gx}:${gy}:${expression.eyeColor ?? ''}`
    if (key !== this.drawn) {
      this.drawn = key
      drawFace(this.face, eyes, this.colors, [gx, gy], expression.eyeColor)
      this.faceTexture.needsUpdate = true
    }
  }
}

const clamp = (n: number) => Math.max(-1, Math.min(1, n))
const approach = (from: number, to: number, ease: number) => {
  const next = from + (to - from) * ease
  return Math.abs(to - next) < 0.001 ? to : next
}
const between = ([a, b]: [number, number]) => a + Math.random() * (b - a)
