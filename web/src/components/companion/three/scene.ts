// Skyny 3D en marcha: el modelo (`model.ts`), sus expresiones (`states.ts`) y la animación de las
// secciones 12 y 13 del estudio: gesto de entrada de cada estado, fundido de colores y postura al
// cambiar, letreros en la pantalla, flotar, confeti, parpadeo y miradas en reposo; más las poses
// y la mirada del login. Lo usan el login y el estudio (`skyny-studio.html`).
import {
  ACESFilmicToneMapping,
  CanvasTexture,
  Color,
  Group,
  Mesh,
  MeshBasicMaterial,
  PerspectiveCamera,
  PointLight,
  Quaternion,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
  type Material,
  type Object3D,
} from 'three'
import type { CompanionState } from '../state'
import { buildAccessory, disposeTree, GRIP_AT, HAND_SYMBOLS, SPEED_X } from './accessories'
import { drawFace, FACE_PX, type FaceEyes } from './face'
import { drawLed, ledMessage, ledTypingFrame } from './led'
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
  accessoryFade,
  blend,
  ENTRY_MS,
  entryGesture,
  errorShake,
  executionBurst,
  executionTravel,
  handLift,
  popIn,
  restLook,
  reveal,
  successHop,
  thinkingLook,
} from './motion'
import { EXPRESSIONS_3D } from './states'
import type { SkynyColors } from './tokens'

/** Manos sobre la cara, como en el 2D: `cover` tapa los dos ojos y `peek` deja ver uno. */
export type Pose3D = 'cover' | 'peek' | undefined

/** Cómo se llega a un estado (lo que cuenta el letrero). */
export interface StateChange {
  /** Letrero propio en vez del del estado, p. ej. «REINTENTO 1/2». */
  message?: string
}

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
/** La carcasa de la cara y la hebilla: las bandas, más oscuras. */
const HARDWARE_SHADE = 0.52
/** Estados de reposo: miran alrededor, parpadean y siguen al puntero si se acerca a la cara. */
const RESTING: CompanionState[] = ['calm', 'ready']
/** Radio (px) alrededor de la cara en el que Skyny sigue al puntero. */
const POINTER_REACH = 200

interface Retiring {
  group: Group
  materials: Map<Material, number>
  at: number
  /** Las líneas de velocidad, cuando se cancela: se recogen en vez de desvanecerse. */
  bars?: { visible: boolean; width: number; x: number }[]
}

interface Transform {
  object: Object3D
  position: Vector3
  quaternion: Quaternion
  scale: Vector3
}

export interface SceneOptions {
  /** Hace los gestos de entrada al cambiar de estado (no con movimiento reducido). */
  gestures?: boolean
  /** Escribe en la pantalla qué hace al cambiar de estado (por defecto, sí). */
  messages?: boolean
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
  private retiring: Retiring[] = []
  private colors: SkynyColors
  private state: CompanionState = 'calm'
  private started = false
  private readonly gestures: boolean
  private readonly messages: boolean
  /** Cuándo empieza (o empezó) el gesto de entrada del estado; null si no hay o ya acabó. */
  private gestureAt: number | null = null
  /** Cuándo se llegó al estado: de ahí cuentan las miradas en reposo. */
  private stateAt = 0
  private errorEyes = false
  /** Pensando: cuándo empezó el guiño (el ojo izquierdo se va cerrando). */
  private winkAt: number | null = null
  /** Al ir a Revisando desde una pose, la lupa espera a que bajen las manos. */
  private readyAt: number | null = null
  private message: { text: string; at: number } | null = null
  private progress: [number, number] | null = null
  private showingProgress = false
  private compact = false
  private palette: { from: Color; to: Color; color: Color }[] | null = null
  private paletteAt = 0
  private poseFrom: Transform[] | null = null
  private poseAt = 0
  private unblended: Transform[] | null = null
  private pose: Pose3D
  private poseAmount = 0
  private peekAmount = 0
  /** Hacia dónde mira, de -1 a 1 en cada eje, y por dónde va de camino. */
  private aim: [number, number] = [0, 0]
  private gaze: [number, number] = [0, 0]
  /** El puntero (coordenadas de la ventana) y la mirada en píxeles de pantalla que provoca. */
  private pointerAt: { x: number; y: number } | null = null
  private pointerGaze: [number, number] = [0, 0]
  private pointerTime: number | null = null
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
    this.messages = options.messages ?? true
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

  /** Cambia los colores (tema claro u oscuro, o paleta personalizada), sin fundido. */
  setColors(colors: SkynyColors) {
    this.colors = colors
    const floor = this.model.floor.material
    floor.map?.dispose()
    floor.map = floorTexture(colors.dark ? colors.glow : '#0f172a', colors.dark)
    floor.needsUpdate = true
    this.palette = null
    this.paintColors(false)
    this.drawn = ''
  }

  /**
   * Cambia de expresión. Con gestos, funde colores y postura en 300 ms, hace el gesto de entrada
   * del nuevo estado y, al acabarlo, escribe el letrero. Volver al mismo estado repite el gesto.
   */
  setState(state: CompanionState, change: StateChange = {}) {
    const now = performance.now()
    const from = this.state
    const animate = this.gestures && this.started
    this.restorePose()
    this.poseFrom = animate ? this.capturePose() : null
    this.poseAt = now
    this.state = state
    this.stateAt = now
    this.showingProgress = false

    // Ir a Revisando con las manos en la cara: primero las baja y luego saca la lupa.
    this.readyAt = null
    if (state === 'reviewing' && this.poseAmount > 0.001) {
      this.pose = undefined
      this.poseAmount = 0
      this.peekAmount = 0
      if (animate) this.readyAt = now + 300
    }
    const start = this.readyAt ?? now
    this.gestureAt = this.gestures && EXPRESSIONS_3D[state].entry ? start : null
    this.errorEyes = state === 'error' && this.gestureAt !== null
    this.winkAt = state === 'thinking' && this.gestures ? now : null

    // El letrero, al acabar el gesto; en Éxito va primero y el salto después.
    const cancelling = from === 'running' && state === 'cancelled' && animate
    const text = ledMessage(state, this.compact, change.message)
    this.message = !this.messages
      ? null
      : {
          text,
          at: !this.gestures
            ? now
            : state === 'success'
              ? now
              : cancelling
                ? now + 500
                : start + ENTRY_MS,
        }
    if (state === 'success' && this.gestureAt !== null && this.message)
      this.gestureAt = now + [...text].length * 90 + 1000

    this.paintColors(animate)
    this.swapAccessory(now, animate, cancelling)
    this.accessory.visible = this.readyAt === null
    this.started = true
    this.drawn = ''
  }

  get current() {
    return this.state
  }

  /** Está con algo a medias: el gesto, el letrero o un contador. */
  get busy() {
    return this.gestureAt !== null || this.message !== null || this.showingProgress
  }

  /** Enseña un contador (p. ej. 2/5) mientras Ejecutando; `null` lo quita. */
  setProgress(done: number | null, total = 0) {
    this.progress = done === null ? null : [done, total]
    this.drawn = ''
  }

  /** Está enseñando el contador de progreso en la pantalla. */
  get progressShown() {
    return this.showingProgress
  }

  /** Letreros cortos (Skyny pequeño): «IDEA» en vez de «PENSANDO...», y el contador enseguida. */
  setCompact(on: boolean) {
    this.compact = on
    this.drawn = ''
  }

  setPose(pose: Pose3D) {
    this.pose = pose
  }

  /** Hacia dónde mira: de -1 a 1 en horizontal y vertical, (0, 0) al frente. */
  lookAt(x: number, y: number) {
    this.aim = [clamp(x), clamp(y)]
  }

  /**
   * Dónde está el puntero (coordenadas de la ventana), o null si se fue. En reposo, si pasa cerca
   * de la cara, Skyny lo sigue con los ojos.
   */
  pointer(at: { x: number; y: number } | null) {
    this.pointerAt = at
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

  /** Parpadea ya (no mientras escribe). */
  blink() {
    if (this.message && performance.now() >= this.message.at) return
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

  /** Colores del estado actual; con `fade`, fundidos desde los que hay. */
  private paintColors(fade: boolean) {
    const body = this.colors.body[this.state]
    const { materials } = this.model
    const band = new Color(body).lerp(BAND_SHADE, 0.72)
    const targets: [Color, Color][] = [
      [materials.body.color, new Color(body)],
      [materials.band.color, band],
      [materials.hardware.color, band.clone().multiplyScalar(HARDWARE_SHADE)],
      [this.glowLight.color, new Color(body)],
    ]
    materials.node.color.set(this.colors.node)
    if (fade) {
      this.palette = targets.map(([color, to]) => ({ color, from: color.clone(), to }))
      this.paletteAt = performance.now()
    } else {
      this.palette = null
      targets.forEach(([color, to]) => color.copy(to))
    }
  }

  /** Cambia el accesorio; el que se va se desvanece (o, al cancelar, se recogen las líneas). */
  private swapAccessory(now: number, fade: boolean, cancelling: boolean) {
    const outgoing = this.accessory
    if (outgoing.children.length && fade && outgoing.visible) {
      const materials = new Map<Material, number>()
      outgoing.traverse((o) => {
        if (!(o instanceof Mesh)) return
        for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
          if (materials.has(m)) continue
          materials.set(m, m.opacity)
          m.transparent = true
          m.depthWrite = false
        }
      })
      this.retiring.push({
        group: outgoing,
        materials,
        at: now,
        bars: cancelling
          ? outgoing.children.map((b) => ({
              visible: b.visible,
              width: b.scale.x,
              x: b.position.x,
            }))
          : undefined,
      })
    } else {
      this.model.root.remove(outgoing)
      disposeTree(outgoing)
    }
    this.accessory = buildAccessory(EXPRESSIONS_3D[this.state].accessory, {
      body: this.colors.body[this.state],
      confetti: this.colors.confetti,
    })
    this.model.root.add(this.accessory)
  }

  private updateRetiring(now: number) {
    this.retiring = this.retiring.filter((r) => {
      const ms = now - r.at
      if (r.bars) {
        // Cancelar: las líneas de velocidad se recogen una detrás de otra.
        r.group.children.forEach((bar, i) => {
          const was = r.bars![i]
          const left = 1 - blend(ms - i * 55)
          bar.visible = was.visible && left > 0
          bar.scale.x = Math.max(was.width * left, 1e-4)
          bar.position.x = was.x
        })
        if (ms < 300 + r.bars.length * 55) return true
      } else {
        const alpha = accessoryFade(ms)
        r.materials.forEach((opacity, m) => (m.opacity = opacity * alpha))
        if (alpha > 0) return true
      }
      this.model.root.remove(r.group)
      disposeTree(r.group)
      return false
    })
  }

  private capturePose(): Transform[] {
    const { root, hands } = this.model
    return [root, ...hands].map((object) => ({
      object,
      position: object.position.clone(),
      quaternion: object.quaternion.clone(),
      scale: object.scale.clone(),
    }))
  }

  /** Deja la postura como la calculó la animación, sin el fundido del fotograma anterior. */
  private restorePose() {
    for (const t of this.unblended ?? []) {
      t.object.position.copy(t.position)
      t.object.quaternion.copy(t.quaternion)
      t.object.scale.copy(t.scale)
    }
    this.unblended = null
  }

  /** Funde la postura del estado anterior con la nueva durante 300 ms. */
  private blendPose(now: number) {
    if (!this.poseFrom) return
    const k = blend(now - this.poseAt)
    if (k >= 1) {
      this.poseFrom = null
      return
    }
    this.unblended = this.capturePose()
    for (const from of this.poseFrom) {
      const { object } = from
      object.position.lerpVectors(from.position, object.position.clone(), k)
      object.quaternion.slerpQuaternions(from.quaternion, object.quaternion.clone(), k)
      object.scale.lerpVectors(from.scale, object.scale.clone(), k)
    }
  }

  /** La mirada (px de pantalla) hacia el puntero si está cerca de la cara; si no, vuelve a 0. */
  private followPointer(now: number, active: boolean) {
    let x = 0
    let y = 0
    let near = false
    if (active && this.pointerAt) {
      const box = this.renderer.domElement.getBoundingClientRect()
      const p = this.model.screen.getWorldPosition(new Vector3()).project(this.camera)
      const dx = this.pointerAt.x - (box.left + ((p.x + 1) * box.width) / 2)
      const dy = this.pointerAt.y - (box.top + ((1 - p.y) * box.height) / 2)
      const distance = Math.hypot(dx, dy)
      near = p.z >= -1 && p.z <= 1 && distance < POINTER_REACH
      if (near) {
        const strength = Math.min(1, (POINTER_REACH - distance) / 55)
        x = clamp(dx / 95) * 13 * strength
        y = clamp(dy / 95) * 9 * strength
      }
    }
    const delta = this.pointerTime === null ? 16 : Math.max(0, Math.min(50, now - this.pointerTime))
    this.pointerTime = now
    const k = active ? 1 - Math.exp(-delta / 100) : 1
    this.pointerGaze[0] += (x - this.pointerGaze[0]) * k
    this.pointerGaze[1] += (y - this.pointerGaze[1]) * k
    return near || Math.abs(this.pointerGaze[0]) + Math.abs(this.pointerGaze[1]) > 0.5
  }

  private update(now: number, animated: boolean) {
    const t = (now - this.start) / 1000
    const ease = animated ? 0.16 : 1
    const { root, hands, head, floor } = this.model
    const state = this.state
    const expression = EXPRESSIONS_3D[state]
    this.restorePose()
    this.updateRetiring(now)
    if (this.palette) {
      const k = animated ? blend(now - this.paletteAt) : 1
      for (const p of this.palette) p.color.copy(p.from).lerp(p.to, k)
      if (k >= 1) this.palette = null
    }
    if (this.readyAt !== null && now >= this.readyAt) {
      this.accessory.visible = true
      this.readyAt = null
    }

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
      case 'sleeping':
        pieces.forEach((letter, i) =>
          grow(letter, reveal(elapsed - 200, i), letter.userData.scale as number),
        )
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

    // Manos: la derecha agarra la lupa o levanta su símbolo; luego, las poses del login.
    const [left, right] = hands
    left.position.set(...HANDS[0])
    right.position.set(
      HANDS[1][0] - reviewing * 0.09,
      HANDS[1][1] + reviewing * 0.04,
      HANDS[1][2] + reviewing * 0.27,
    )
    const accessory = expression.accessory
    const symbol = accessory ? HAND_SYMBOLS[accessory] : undefined
    if (symbol) {
      const lift = handLift(elapsed)
      right.position.y += 0.9 * lift
      right.position.z += 0.14 * lift
      const u = (elapsed - 420) / 320
      grow(pieces[0], u > 0 ? 1 : 0, symbol * popIn(elapsed - 420))
    }
    const grip = this.accessory.getObjectByName('grip')
    if (grip) {
      grip.position.set(
        GRIP_AT.x - reviewing * 0.44,
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
        hand.position.set(x - side * 0.55 * over, y + 0.38 * over, z + 0.82 * forward)
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

    // Las manos flotan con un poco de retraso respecto al cuerpo (no en pausa ni con gestos).
    if (
      animated &&
      this.idle &&
      state !== 'paused' &&
      this.poseAmount === 0 &&
      this.gestureAt === null &&
      this.gestures
    ) {
      const bobLag = Math.sin((t - 0.23) * 2) - Math.sin(t * 2)
      const bankLag = Math.sin((t - 0.23) * 1.6) - Math.sin(t * 1.6)
      hands.forEach((hand, i) => {
        const offset = 0.047 * bobLag + (i === 0 ? -1 : 1) * 0.022 * bankLag
        hand.position.y += offset
        if (i === 1 && grip) grip.position.y += offset
      })
    }

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

    this.blendPose(now)

    // El símbolo de la mano la sigue (en el espacio de Skyny, sin girarse hacia la cámara).
    if (symbol && pieces[0]) {
      const marker = pieces[0]
      marker.position.copy(right.position)
      marker.position.z += 0.34
      // El «?» tiene la tinta por encima de su origen: se centra.
      if (accessory === 'alert') marker.translateY(-0.055 * marker.scale.y)
    }

    if (!this.drawLed(now, animated)) this.drawEyes(now, animated, amount, thinking)
  }

  /** El letrero o el contador, si toca; devuelve si ha ocupado la pantalla. */
  private drawLed(now: number, animated: boolean) {
    const counting = this.state === 'running' && this.progress !== null
    if (this.message && now >= this.message.at) {
      // En compacto, el contador de Ejecutando va en lugar del letrero.
      if (counting && this.compact) this.message = null
      else {
        const { text } = this.message
        const frame = ledTypingFrame(
          now - this.message.at,
          [...text].length,
          !this.gestures || !animated,
        )
        if (!frame.finished) {
          this.showingProgress = false
          return this.paintLed(text, frame)
        }
        this.message = null
      }
    }
    // El contador va después del letrero.
    this.showingProgress = counting && this.message === null
    if (!this.showingProgress) return false
    const counter = `${this.progress![0]}/${this.progress![1]}`
    return this.paintLed(counter, { count: [...counter].length, cursor: false, big: true })
  }

  private paintLed(text: string, frame: { count: number; cursor: boolean; big?: boolean }) {
    const key = `led:${text}:${frame.count}:${frame.cursor}`
    if (key !== this.drawn) {
      this.drawn = key
      drawLed(this.face, text, { ...frame, error: this.state === 'error' })
      this.faceTexture.needsUpdate = true
    }
    return true
  }

  /** Ojos: los del estado, el guiño de Pensando, el parpadeo y hacia dónde miran. */
  private drawEyes(now: number, animated: boolean, amount: number, thinking: number) {
    const state = this.state
    const expression = EXPRESSIONS_3D[state]
    const resting = RESTING.includes(state)
    const lively = animated && this.idle && this.gestures

    // En reposo: mira alrededor, parpadea, y sigue al puntero si se acerca a la cara.
    let rest: [number, number] = [0, 0]
    let restBlink = false
    const tracking = this.followPointer(now, resting && lively)
    if (resting && lively) {
      const look = restLook(now - this.stateAt)
      rest = tracking ? [this.pointerGaze[0], this.pointerGaze[1]] : [look.gaze, 0]
      restBlink = !tracking && look.blinking
    } else if (state === 'thinking' && lively && this.gestureAt === null) {
      rest = thinkingLook(Math.max(0, now - this.stateAt - ENTRY_MS))
    }

    if (animated && !resting && BLINKS.includes(expression.eyes)) {
      if (!this.nextBlink) this.nextBlink = now + between(BLINK_EVERY)
      if (now >= this.nextBlink) {
        this.blinkUntil = now + BLINK_MS
        this.nextBlink = now + between(BLINK_EVERY)
      }
    }
    const eyes: FaceEyes =
      now < this.blinkUntil || restBlink ? 'bars' : this.errorEyes ? 'error-pills' : expression.eyes
    const reviewing = state === 'reviewing' ? amount : 0
    const shift = reviewing * 12 - thinking * 16
    const gx = Math.round(shift + rest[0] + this.gaze[0] * 30)
    const gy = Math.round(
      (state === 'thinking' ? -Math.abs(shift) : 0) + rest[1] + this.gaze[1] * 22,
    )
    let wink = 1
    if (this.winkAt !== null) {
      wink = Math.round(blend(now - this.winkAt - 160) * 20) / 20
      if (wink >= 1) this.winkAt = null
    }
    const key = `${eyes}:${gx}:${gy}:${expression.eyeColor ?? ''}:${wink}`
    if (key !== this.drawn) {
      this.drawn = key
      drawFace(this.face, eyes, this.colors, [gx, gy], expression.eyeColor, wink)
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
