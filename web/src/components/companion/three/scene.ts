// @ts-nocheck -- motor portado del estudio v4.3 sin tipar; la API que usa la web la tipa
// `SkynyScene` (scene.ts) y la prueban los tests de Skyny3D.
// Portado del estudio v4.3 (plans/skyny-3d/skyny-v4.3.html).
import * as THREE from 'three'
import { clamp01, smoothstep, easeOutCubic } from './math'
import { STATE_CONFIG, LED_MESSAGES, LED_COMPACT } from './states'
import { buildSkynyModel, HAND_REST_POS, HAND_SPHERE_RADIUS, BASE_Y, BODY_RADIUS } from './model'
import { createAccessory, delegationDispatch, cooldownCharge, BADGE_SCALE } from './accessories'
import { drawLedScreen, drawEyesScreen, ledWidth } from './screen'

/** Letreros deslizantes: velocidad (px por segundo, en un visor de 512) y tamaño de letra. */
const SCROLL_SPEED = 210
const SCROLL_SCALE = 1.1
import { createExtras, type BackKind, type FaceKind, type HatKind } from './extras'
import type { SkynyState } from './states'
import type { SkynyColors } from './tokens'

/** Pose del login: taparse los ojos o mirar de reojo. */
export type Pose3D = 'cover' | 'peek' | undefined

/** Cómo se llega a un estado (lo que cuenta el letrero). */
export interface StateChange {
  /** Letrero propio en vez del del estado, p. ej. «REINTENTO 1/2». */
  message?: string
}

export interface SceneOptions {
  /** Hace los gestos de entrada al cambiar de estado (no con movimiento reducido). */
  gestures?: boolean
  /** Escribe en la pantalla qué hace al cambiar de estado (por defecto, sí). */
  messages?: boolean
}

export class SkynyScene {
  // Motor del estudio v4.3, en JavaScript sin tipar: cualquier propiedad vale.
  [key: string]: any

  constructor(canvas: HTMLCanvasElement, colors: SkynyColors, options: SceneOptions = {}) {
    this.colors = colors
    this.gestures = options.gestures ?? true
    this.messages = options.messages ?? true

    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1.05
    this.renderer.outputColorSpace = THREE.SRGBColorSpace

    this.camera = new THREE.PerspectiveCamera(36, 1, 0.1, 50)
    // Encuadre de la web (el estudio lo cambia con sus vistas).
    this.camera.position.set(0, 0.12, 4.4)
    this.camera.lookAt(0, 0.1, 0)
    this.scene = new THREE.Scene()

    // Entorno de estudio reflectante (PMREM)
    const pmrem = new THREE.PMREMGenerator(this.renderer)
    const envScene = new THREE.Scene()
    envScene.background = new THREE.Color(0xcbd5e1)
    for (const [x, y, z, w, h] of [
      [-3, 2, 2, 3, 5],
      [3, 1, 1, 2, 4],
      [0, 4, 0, 5, 3],
    ]) {
      const panel = new THREE.Mesh(
        new THREE.PlaneGeometry(w, h),
        new THREE.MeshBasicMaterial({ color: 0xffffff }),
      )
      panel.position.set(x, y, z)
      panel.lookAt(0, 0, 0)
      envScene.add(panel)
    }
    this.scene.environment = pmrem.fromScene(envScene, 0.04).texture
    pmrem.dispose()

    // Iluminación de estudio
    this.scene.add(new THREE.AmbientLight(0xffffff, 0.85))
    const keyLight = new THREE.DirectionalLight(0xffffff, 1.15)
    keyLight.position.set(3, 5, 4)
    const rimLight = new THREE.DirectionalLight(0xbae6fd, 1.25)
    rimLight.position.set(-3, 3, -4)
    const fillLight = new THREE.DirectionalLight(0xffffff, 0.4)
    fillLight.position.set(-4, 1, 2)
    this.scene.add(keyLight, rimLight, fillLight)

    this.glowLight = new THREE.PointLight(0xffffff, 2.2, 3.5)
    this.glowLight.position.set(0, -0.65, 0)
    this.scene.add(this.glowLight)

    const faceCanvas = document.createElement('canvas')
    faceCanvas.width = 512
    faceCanvas.height = 512
    this.faceCanvas = faceCanvas
    this.faceCtx = faceCanvas.getContext('2d')
    this.faceTexture = new THREE.CanvasTexture(faceCanvas)
    this.faceTexture.colorSpace = THREE.SRGBColorSpace

    this.lensTarget = new THREE.WebGLRenderTarget(512, 512, {
      minFilter: THREE.LinearFilter,
      magFilter: THREE.LinearFilter,
      format: THREE.RGBAFormat,
    })

    this.model = buildSkynyModel(this.faceTexture)
    this.accessory = new THREE.Group()
    this.model.root.add(this.accessory)
    this.scene.add(this.model.root, this.model.floor)

    // Luz frontal reactiva acoplada al visor (Visor Subsurface Light)
    this.visorLight = new THREE.PointLight(0x49c2ff, 0, 2.2)
    this.visorLight.position.set(0, 0, 0.56)
    this.model.visor.add(this.visorLight)

    // Estado interno
    this.state = 'calm'
    this.started = false
    this.gestureAt = null
    this.stateAt = 0
    this.winkAt = null
    this.errorEyes = false
    this.message = null
    this.progress = null
    this.showingProgress = false
    this.compact = false
    this.paletteAnim = null
    this.poseFrom = null
    this.poseAt = 0
    this.unblended = null
    this.pose = undefined
    this.poseAmount = 0
    this.peekAmount = 0
    this.aim = [0, 0]
    this.gaze = [0, 0]
    this.pointerAt = null
    this.pointerGaze = [0, 0]
    this.pointerTime = null
    this.idle = true
    this.idleAmount = 1
    this.drawnKey = ''
    this.nextBlink = 0
    this.blinkUntil = 0
    this.frame = 0
    this.startTime = performance.now()
    this.lastUpdateTime = performance.now()
    this.retiring = []

    // Micro-animaciones interactivas
    this.pokeAt = null
    this.pokeCount = 0
    this.dizzyUntil = 0
    this.lastCamDir = null
    this.camSpinAccum = 0
    this.prevOx = 0
    this.prevOy = 0
    this.saccadeMag = 0
    this.saccadeDir = 1
    this.smoothTestIdx = 1

    // Física de resorte (Damped Spring Physics) para las manos
    this.handSprings = HAND_REST_POS.map((pos) => ({
      pos: new THREE.Vector3(...pos),
      vel: new THREE.Vector3(0, 0, 0),
      target: new THREE.Vector3(...pos),
    }))

    this.setColors(colors)
    // Gorro, complemento de cara y de espalda (se eligen en Ajustes › Companion).
    this.extras = createExtras(this)
  }

  setHat(kind: HatKind) {
    this.extras.setHat(kind)
  }
  setFace(kind: FaceKind) {
    this.extras.setFace(kind)
  }
  setBack(kind: BackKind) {
    this.extras.setBack(kind)
  }

  /**
   * Escribe un letrero ya, por encima de lo que hubiera: tecleado o, con `scroll`, deslizándose de
   * derecha a izquierda. Con varios, uno tras otro (tecleados). `error` lo pinta en rojo.
   */
  say(text: string | string[], opts: { scroll?: boolean; error?: boolean; hold?: number } = {}) {
    const [first, ...rest] = Array.isArray(text) ? text : [text]
    const error = !!opts.error
    this.message = {
      text: first,
      at: performance.now(),
      scroll: !!opts.scroll,
      error,
      hold: opts.hold ?? 900,
    }
    this.sayQueue = rest.map((t) => ({ text: t, error, hold: 900 }))
    this.drawnKey = ''
  }

  /**
   * Letreros que pasan deslizándose por la pantalla, uno tras otro, mientras no hay otro letrero
   * (`gap` ms de ojos entre uno y otro). Al acabar la lista vuelve a empezar por `loopFrom`.
   */
  setTicker(
    lines: string[] | null,
    opts: { gap?: number; loopFrom?: number; delay?: number } = {},
  ) {
    this.ticker = lines?.length
      ? { lines, i: 0, gap: opts.gap ?? 2600, loopFrom: opts.loopFrom ?? 0 }
      : null
    this.tickerAt = performance.now() + (opts.delay ?? 0)
  }

  /** ¿Está el punto (de la ventana) encima de Skyny? */
  hitTest(x: number, y: number) {
    const r = this.renderer.domElement.getBoundingClientRect()
    if (!r.width || !r.height) return false
    const ndc = new THREE.Vector2(
      ((x - r.left) / r.width) * 2 - 1,
      -((y - r.top) / r.height) * 2 + 1,
    )
    this.raycaster ??= new THREE.Raycaster()
    this.raycaster.setFromCamera(ndc, this.camera)
    return this.raycaster.intersectObjects(this.model.root.children, true).length > 0
  }

  /** Gira la cámara alrededor de Skyny (radianes); si gira deprisa, Skyny se marea. */
  setOrbit(angle: number) {
    this.orbitBase ??= this.camera.position.clone()
    const target = new THREE.Vector3(0, 0.1, 0)
    const offset = this.orbitBase
      .clone()
      .sub(target)
      .applyAxisAngle(new THREE.Vector3(0, 1, 0), angle)
    this.camera.position.copy(target).add(offset)
    this.camera.lookAt(target)
  }

  poke() {
    const now = performance.now()
    this.pokeAt = now
    this.pokeCount = (this.pokeCount + 1) % 3
    this.drawnKey = ''
  }

  setColors(colors: SkynyColors) {
    this.colors = colors
    const floorMat = this.model.floor.material
    if (floorMat.map) floorMat.map.dispose()

    const c = document.createElement('canvas')
    c.width = 256
    c.height = 256
    const ctx = c.getContext('2d')
    const g = ctx.createRadialGradient(128, 128, 0, 128, 128, 124)
    g.addColorStop(0, 'rgba(0,0,0,1)')
    g.addColorStop(0.45, 'rgba(0,0,0,0.75)')
    g.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, 256, 256)
    ctx.globalCompositeOperation = 'source-in'
    ctx.globalAlpha = 0.3
    ctx.fillStyle = colors.dark ? colors.glow : '#0f172a'
    ctx.fillRect(0, 0, 256, 256)

    floorMat.map = new THREE.CanvasTexture(c)
    floorMat.map.colorSpace = THREE.SRGBColorSpace
    floorMat.needsUpdate = true

    this.paintColors(false)
    this.drawnKey = ''
  }

  paintColors(animate) {
    const hex = this.colors.body[this.state]
    const { materials } = this.model
    const darkTint = new THREE.Color(hex).lerp(new THREE.Color('#05070f'), 0.72)
    const targets = [
      [materials.body.color, new THREE.Color(hex)],
      [materials.band.color, darkTint],
      [materials.hardware.color, darkTint.clone().multiplyScalar(0.52)],
      [this.glowLight.color, new THREE.Color(hex)],
    ]
    materials.node.color.set(this.colors.node)
    this.model.hands.forEach((h) => h.material.color.set(this.colors.node))
    if (animate) {
      this.paletteAnim = {
        list: targets.map(([col, to]) => ({ col, from: col.clone(), to })),
        at: performance.now(),
      }
    } else {
      this.paletteAnim = null
      targets.forEach(([col, to]) => col.copy(to))
    }
  }

  setState(nextState: SkynyState, opts: StateChange = {}) {
    const now = performance.now()
    const prev = this.state
    const anim = this.gestures && this.started

    this.restorePose()
    this.poseFrom = anim ? this.capturePose() : null
    this.poseAt = now
    this.state = nextState
    this.stateAt = now
    this.showingProgress = false

    if (nextState === 'reviewing' && this.poseAmount > 0.001) {
      this.pose = undefined
      this.poseAmount = 0
      this.peekAmount = 0
    }

    this.gestureAt = this.gestures && STATE_CONFIG[nextState].entry ? now : null
    this.errorEyes = nextState === 'error' && this.gestureAt !== null
    this.winkAt =
      (nextState === 'thinking' || nextState === 'healing' || nextState === 'recalling') &&
      this.gestures
        ? now
        : null

    const wasCancelled = prev === 'running' && nextState === 'cancelled' && anim
    let msgText = opts.message ?? LED_MESSAGES[nextState]
    if (this.compact && msgText.length > 8) msgText = LED_COMPACT[nextState] ?? msgText

    this.message = this.messages
      ? {
          text: msgText,
          at: this.gestures
            ? nextState === 'success'
              ? now
              : wasCancelled
                ? now + 500
                : now + 1450
            : now,
        }
      : null

    if (nextState === 'success' && this.gestureAt !== null && this.message) {
      this.gestureAt = now + [...msgText].length * 90 + 1000
    }

    this.paintColors(anim)
    this.swapAccessory(now, anim)
    this.started = true
    this.drawnKey = ''
  }

  swapAccessory(now, animate) {
    const old = this.accessory
    if (old.children.length && animate) {
      const mats = new Map()
      old.traverse((o) => {
        if (o instanceof THREE.Mesh) {
          for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
            if (!mats.has(m)) {
              mats.set(m, m.opacity)
              m.transparent = true
              m.depthWrite = false
            }
          }
        }
      })
      this.retiring.push({ group: old, mats, at: now })
    } else {
      this.model.root.remove(old)
      this.disposeGroup(old)
    }
    this.accessory = createAccessory(STATE_CONFIG[this.state].accessory, {
      body: this.colors.body[this.state],
      confetti: this.colors.confetti,
    })
    this.model.root.add(this.accessory)
  }

  /** Libera geometrías, materiales y sus texturas de un grupo (al quitar un accesorio). */
  disposeGroup(group) {
    const mats = new Set()
    group.traverse((o) => {
      o.geometry?.dispose()
      if (o.material)
        (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => mats.add(m))
    })
    mats.forEach((m) => {
      for (const v of Object.values(m)) if (v && v.isTexture) v.dispose()
      m.dispose()
    })
  }

  get busy() {
    return this.gestureAt !== null || this.message !== null || this.showingProgress
  }
  get current() {
    return this.state
  }
  get progressShown() {
    return this.showingProgress
  }

  setProgress(done: number | null, total = 0) {
    this.progress = done === null ? null : [done, total]
    this.drawnKey = ''
  }
  setCompact(v: boolean) {
    this.compact = v
    this.drawnKey = ''
  }
  setPose(p: Pose3D) {
    this.pose = p
  }
  lookAt(x: number, y: number) {
    this.aim = [Math.max(-1, Math.min(1, x)), Math.max(-1, Math.min(1, y))]
  }
  pointer(pt: { x: number; y: number } | null) {
    this.pointerAt = pt
  }
  setIdle(v) {
    this.idle = v
  }
  setWireframe(v) {
    this.model.root.traverse((o) => {
      if (o instanceof THREE.Mesh && 'wireframe' in o.material) o.material.wireframe = v
    })
  }
  blink() {
    this.blinkUntil = performance.now() + 140
  }

  capturePose() {
    return [this.model.root, this.model.visor, ...this.model.hands].map((obj) => ({
      obj,
      pos: obj.position.clone(),
      quat: obj.quaternion.clone(),
      scale: obj.scale.clone(),
    }))
  }
  restorePose() {
    for (const item of this.unblended ?? []) {
      item.obj.position.copy(item.pos)
      item.obj.quaternion.copy(item.quat)
      item.obj.scale.copy(item.scale)
    }
    this.unblended = null
  }
  blendPose(now) {
    if (!this.poseFrom) return
    const t = smoothstep((now - this.poseAt) / 300)
    if (t >= 1) {
      this.poseFrom = null
      return
    }
    this.unblended = this.capturePose()
    for (const item of this.poseFrom) {
      item.obj.position.lerpVectors(item.pos, item.obj.position.clone(), t)
      item.obj.quaternion.slerpQuaternions(item.quat, item.obj.quaternion.clone(), t)
      item.obj.scale.lerpVectors(item.scale, item.obj.scale.clone(), t)
    }
  }

  /**
   * Tamaño del lienzo. Con `room` > 1 el lienzo crece y la cámara se aleja en la misma proporción:
   * Skyny se ve igual de grande y quedan márgenes para globos, sombreros y gestos.
   */
  resize(w: number, h: number = w, room = 1) {
    this.renderer.setSize(w, h, false)
    this.camera.aspect = w / h
    this.camera.zoom = 1 / room
    this.camera.updateProjectionMatrix()
    const dpr = this.renderer.getPixelRatio()
    this.lensTarget.setSize(Math.max(1, Math.floor(w * dpr)), Math.max(1, Math.floor(h * dpr)))
  }

  snapshot() {
    this.renderFrame()
    return this.renderer.domElement.toDataURL('image/png')
  }

  renderFrame() {
    const magGlass = this.accessory?.getObjectByName('magGlass')
    if (magGlass && magGlass.visible && magGlass.material?.uniforms) {
      const mat = magGlass.material
      magGlass.visible = false
      const prevTarget = this.renderer.getRenderTarget()
      this.renderer.setRenderTarget(this.lensTarget)
      this.renderer.clear()
      this.renderer.render(this.scene, this.camera)
      this.renderer.setRenderTarget(prevTarget)
      magGlass.visible = true

      this.model.root.updateMatrixWorld(true)
      const centerWorld = magGlass.getWorldPosition(new THREE.Vector3())
      const ndc = centerWorld.project(this.camera)
      mat.uniforms.tScene.value = this.lensTarget.texture
      mat.uniforms.uResolution.value.set(this.lensTarget.width, this.lensTarget.height)
      mat.uniforms.uLensCenter.value.set(ndc.x * 0.5 + 0.5, ndc.y * 0.5 + 0.5)
    }
    this.renderer.render(this.scene, this.camera)
  }

  play(onTick?: () => void) {
    if (this.frame) return
    const loop = (now) => {
      this.frame = requestAnimationFrame(loop)
      this.update(now, true)
      this.extras.update(now)
      onTick?.()
      this.renderFrame()
    }
    this.frame = requestAnimationFrame(loop)
  }

  /** Pinta un fotograma quieto, sin flotar ni parpadear (para movimiento reducido). */
  renderStill() {
    const now = performance.now()
    this.update(now, false)
    this.extras.update(now)
    this.renderFrame()
  }

  stop() {
    cancelAnimationFrame(this.frame)
    this.frame = 0
  }

  /** Libera todo lo que tiene en la GPU: geometrías, materiales, texturas y el contexto. */
  dispose() {
    this.stop()
    this.extras.dispose()
    this.disposeGroup(this.scene)
    this.scene.environment?.dispose()
    this.faceTexture.dispose()
    this.lensTarget.dispose()
    this.renderer.dispose()
  }

  update(now: number, animate: boolean) {
    const dtSec = Math.min(0.05, Math.max(0.001, (now - this.lastUpdateTime) / 1000))
    this.lastUpdateTime = now
    const elapsed = (now - this.startTime) / 1000
    const lerpRate = animate ? 0.16 : 1
    const { root, visor, hands, head, floor } = this.model
    const state = this.state
    const cfg = STATE_CONFIG[state]

    this.restorePose()

    // Actualizar accesorios salientes
    this.retiring = this.retiring.filter((item) => {
      const fade = 1 - smoothstep((now - item.at) / 220)
      if (fade > 0) {
        item.mats.forEach((op, m) => {
          m.opacity = op * fade
        })
        return true
      }
      this.model.root.remove(item.group)
      this.disposeGroup(item.group)
      return false
    })

    // Transición suave de colores
    if (this.paletteAnim) {
      const t = animate ? smoothstep((now - this.paletteAnim.at) / 300) : 1
      for (const p of this.paletteAnim.list) p.col.copy(p.from).lerp(p.to, t)
      if (t >= 1) this.paletteAnim = null
    }

    let gestureEnv = 0
    if (this.gestureAt !== null) {
      const dt = now - this.gestureAt
      gestureEnv = smoothstep(dt / 420) * (1 - smoothstep((dt - 950) / 500))
      if (dt >= 1450) this.gestureAt = null
    }

    const stateElapsed = now - this.stateAt
    const dt = this.gestureAt === null ? stateElapsed : now - this.gestureAt
    const loopFade = animate && this.idle ? smoothstep((stateElapsed - 950) / 500) : 0
    const activeFade = animate ? smoothstep(stateElapsed / 320) : 1
    const accChildren = this.accessory.children

    // Detección de mareo al orbitar rápido la cámara alrededor de Skyny
    const camDir = this.camera.position.clone().sub(root.position).normalize()
    if (this.lastCamDir && animate) {
      const angSpeed = this.lastCamDir.angleTo(camDir) / dtSec
      if (angSpeed > 3.6) {
        this.camSpinAccum += (angSpeed - 3.6) * dtSec
        if (this.camSpinAccum > 1.25) {
          this.dizzyUntil = now + 1300
          this.camSpinAccum = 0
        }
      } else {
        this.camSpinAccum = Math.max(0, this.camSpinAccum - dtSec * 1.8)
      }
    }
    if (!this.lastCamDir) this.lastCamDir = new THREE.Vector3()
    this.lastCamDir.copy(camDir)
    const isDizzy = now < this.dizzyUntil
    const dizzyEnv = isDizzy ? smoothstep((this.dizzyUntil - now) / 300) : 0

    // Reacción física al hacer clic ("Poke / Boop")
    let pokeEnv = 0,
      pokeT = 0
    if (this.pokeAt !== null) {
      const pDt = now - this.pokeAt
      if (pDt >= 880) {
        this.pokeAt = null
      } else {
        pokeT = pDt / 880
        pokeEnv = Math.sin(pokeT * Math.PI)
      }
    }

    // Variedad de 3 micro-gestos en 'calm' cada 6 segundos
    const calmCycleIdx = Math.floor(stateElapsed / 6000) % 3
    const curiosityTime = stateElapsed % 6000
    const calmGestureEnv =
      state === 'calm' && !this.pose
        ? smoothstep((curiosityTime - 3900) / 450) *
          (1 - smoothstep((curiosityTime - 4950) / 650)) *
          loopFade
        : 0
    const curiosity = calmCycleIdx === 0 ? calmGestureEnv : 0
    const juggleEnv = calmCycleIdx === 1 ? calmGestureEnv : 0
    const stretchEnv = calmCycleIdx === 2 ? calmGestureEnv : 0

    const sleepBreath = state === 'sleeping' ? Math.sin(stateElapsed / 850) * loopFade : 0
    const cooldown =
      state === 'rate-limited' ? cooldownCharge(stateElapsed, animate && this.idle) : null
    const rechargeBreath =
      cooldown && animate && this.idle
        ? Math.sin(cooldown.tCycle / 950) * (1 - cooldown.progress * 0.7) * activeFade
        : 0
    const cooldownSigh =
      cooldown && animate && this.idle && !cooldown.justFull
        ? Math.pow(Math.max(0, Math.sin(cooldown.tCycle / 680)), 6) * (1 - cooldown.progress)
        : 0
    const glanceMod = cooldown ? cooldown.tCycle % 3400 : 0
    const cooldownGlance =
      cooldown &&
      animate &&
      this.idle &&
      !cooldown.justFull &&
      cooldown.progress < 0.92 &&
      glanceMod > 1200 &&
      glanceMod < 2300
        ? Math.sin(((glanceMod - 1200) / 1100) * Math.PI)
        : 0
    const cooldownReadyPop =
      cooldown && cooldown.justFull
        ? Math.sin(clamp01((cooldown.tCycle - 7400) / 900) * Math.PI)
        : 0
    const readySway = state === 'ready' ? Math.sin(stateElapsed / 470) * loopFade : 0

    const thinkLoop = state === 'thinking' ? Math.sin(elapsed * 2.2) * 0.35 * loopFade : 0

    const revCycleTime = Math.max(0, stateElapsed - 1500) % 4200
    const revRepeatEnv =
      state === 'reviewing' && loopFade > 0
        ? smoothstep(revCycleTime / 450) * (1 - smoothstep((revCycleTime - 2500) / 550)) * loopFade
        : 0
    const reviewActive = state === 'reviewing' ? Math.max(gestureEnv, revRepeatEnv) : 0
    const reviewScanX = state === 'reviewing' ? Math.sin(elapsed * 3.2) * reviewActive : 0
    const reviewScanY = state === 'reviewing' ? Math.cos(elapsed * 4.8) * reviewActive : 0

    const auditLoop = state === 'auditing' ? Math.sin(elapsed * 2.8) * 0.85 * activeFade : 0
    const thinkEnv = state === 'thinking' ? gestureEnv + thinkLoop : 0
    const reviewEnv =
      state === 'reviewing' ? reviewActive : state === 'auditing' ? auditLoop * 0.5 : 0

    const isRunning = state === 'running'
    const runEntryEnv =
      isRunning && this.gestureAt !== null
        ? Math.pow(Math.sin(Math.PI * clamp01((dt - 260) / 680)), 2)
        : 0
    const runLoopEnv = isRunning ? (0.45 + 0.2 * Math.sin(elapsed * 10)) * loopFade : 0
    const runEnv = Math.max(runEntryEnv, runLoopEnv)

    const popChild = (mesh, t, baseScale = 1) => {
      if (!mesh) return
      mesh.visible = t > 0
      mesh.scale.setScalar(Math.max(t, 1e-4) * baseScale)
    }

    const dagStep = Math.floor(elapsed * 2.2) % 5
    let activeDagNodePos = null
    const testCycle = (stateElapsed * 0.00045) % 1
    const activeTestIdx = Math.min(2, Math.floor(testCycle * 3.3))
    const testLerp = animate ? Math.min(1, dtSec * 4.2) : 1
    this.smoothTestIdx += (activeTestIdx - this.smoothTestIdx) * testLerp

    const mergeCycle = (stateElapsed * 0.00072) % 1
    const mergeConv = state === 'merging' ? smoothstep(Math.min(1, mergeCycle / 0.58)) : 0

    const benchCycle = (stateElapsed * 0.00034) % 1
    const benchWinner = Math.floor(stateElapsed * 0.00034) % 2
    const benchSettle = smoothstep(Math.min(1, Math.max(0, (benchCycle - 0.44) / 0.22)))
    const benchReset = 1 - smoothstep(Math.min(1, Math.max(0, (benchCycle - 0.84) / 0.16)))
    const benchHold = benchSettle * benchReset
    const benchStartIn = smoothstep(Math.min(1, benchCycle / 0.14))
    const benchWeighOsc =
      Math.sin(benchCycle * Math.PI * 6) * benchStartIn * (1 - benchSettle) * benchReset * 0.3
    const benchArmTilt = benchWeighOsc + (benchWinner === 0 ? 0.24 : -0.24) * benchHold

    const routingCycle = (stateElapsed * 0.00048) % 1
    const routedModelIdx = Math.floor(routingCycle * 3) % 3
    const routingLock = smoothstep(Math.min(1, ((routingCycle * 3) % 1) / 0.45))
    let activeRouterPos = null
    const recallCycle = (stateElapsed * 0.00042) % 1
    const checkCycle = (stateElapsed * 0.00055) % 1
    const noResPhase = (stateElapsed * 0.00045) % 1

    if (state === 'learning') {
      this.glowLight.intensity = 2.2 + Math.sin(elapsed * 3.6) * 0.9 * activeFade
    } else if (state === 'guardrail') {
      this.glowLight.intensity = 2.5 + Math.sin(elapsed * 10) * 0.6 * activeFade
    } else if (state === 'rate-limited' && cooldown) {
      this.glowLight.intensity = 1.6 + cooldown.progress * 1.3 + cooldownReadyPop * 1.1
    } else {
      this.glowLight.intensity = 2.2
    }

    // Animación de accesorios por estado
    switch (state) {
      case 'guardrail': {
        const shield = this.accessory.getObjectByName('hexShield')
        if (shield) {
          const snapIn = smoothstep(stateElapsed / 180)
          const bounce =
            snapIn > 0 ? 1 + 1.4 * Math.pow(snapIn - 1, 3) + 0.8 * Math.pow(snapIn - 1, 2) : 0
          const impactCycle = (stateElapsed % 2400) / 2400
          const pulse = impactCycle < 0.25 ? Math.sin((impactCycle / 0.25) * Math.PI) : 0

          shield.scale.setScalar(Math.max(1e-4, snapIn * bounce * (1 + pulse * 0.08)))
          shield.position.z = 1.06 + pulse * 0.05
          shield.rotation.z = Math.sin(elapsed * 2.2) * 0.03

          const plate = shield.getObjectByName('shieldPlate')
          if (plate && plate instanceof THREE.Mesh) {
            plate.material.opacity = 0.18 + pulse * 0.26
          }
          const waveRing = shield.getObjectByName('shieldShockwave')
          if (waveRing && waveRing instanceof THREE.Mesh) {
            const wT = clamp01(impactCycle / 0.45)
            waveRing.scale.setScalar(1 + wT * 0.72)
            waveRing.material.opacity = (1 - wT) * 0.85 * snapIn
          }
        }
        break
      }
      case 'benchmarking': {
        const scaleGrp = this.accessory.getObjectByName('balanceScale')
        if (scaleGrp) {
          const appear = smoothstep(stateElapsed / 320)
          const bounce =
            appear > 0 ? 1 + 1.1 * Math.pow(appear - 1, 3) + 0.7 * Math.pow(appear - 1, 2) : 0
          scaleGrp.scale.setScalar(Math.max(1e-4, appear * bounce * 1.18))
          scaleGrp.position.y = -0.14 + Math.sin(elapsed * 2.4) * 0.015

          const arm = scaleGrp.getObjectByName('scaleArm')
          if (arm) {
            arm.rotation.z = animate && this.idle ? benchArmTilt : 0
            ;['scalePanA', 'scalePanB'].forEach((panName, idx) => {
              const pan = arm.getObjectByName(panName)
              if (pan) {
                pan.rotation.z = -arm.rotation.z
                const isWinner = idx === benchWinner
                const winFactor = isWinner ? benchHold : 0

                const dish = pan.getObjectByName('panDish')
                if (dish && dish instanceof THREE.Mesh) {
                  dish.material.color.setHex(winFactor > 0.4 ? 0x10b981 : 0xfde047)
                  dish.material.emissive.setHex(winFactor > 0.4 ? 0x059669 : 0xca8a04)
                  dish.material.emissiveIntensity = 0.3 + winFactor * 0.55
                }
                const payload = pan.getObjectByName('panPayload')
                if (payload) {
                  payload.scale.setScalar(1)
                  payload.position.y = -0.142
                  payload.rotation.y = Math.sin(elapsed * 1.6 + idx) * 0.18
                }
                const winRing = pan.getObjectByName('panWinRing')
                if (winRing && winRing instanceof THREE.Mesh) {
                  winRing.material.opacity = winFactor * (0.65 + 0.3 * Math.sin(elapsed * 9))
                  winRing.scale.setScalar(0.9 + winFactor * (0.25 + Math.sin(elapsed * 6) * 0.06))
                }
              }
            })
          }
        }
        break
      }
      case 'sandbox': {
        const sb = this.accessory.getObjectByName('sandboxCube')
        if (sb) {
          const appear = smoothstep(stateElapsed / 320)
          const bounce =
            appear > 0 ? 1 + 1.1 * Math.pow(appear - 1, 3) + 0.7 * Math.pow(appear - 1, 2) : 0
          sb.scale.setScalar(Math.max(1e-4, appear * bounce))
          sb.rotation.y = Math.sin(elapsed * 1.4) * 0.38
          sb.rotation.x = 0.22 + Math.cos(elapsed * 1.8) * 0.08

          const scanPlane = sb.getObjectByName('sandboxScanPlane')
          if (scanPlane) {
            scanPlane.position.y = Math.sin(elapsed * 3.6) * 0.19
          }
          const walls = sb.getObjectByName('sandboxWalls')
          if (walls && walls instanceof THREE.Mesh) {
            walls.material.opacity = 0.1 + 0.06 * Math.sin(elapsed * 5)
          }

          const agentOrb = sb.getObjectByName('sandboxAgentOrb')
          if (agentOrb) {
            const triWave = (p) => 1 - 4 * Math.abs(Math.round(p - 0.25) - (p - 0.25))
            const limit = 0.145
            agentOrb.position.set(
              triWave(elapsed * 0.62) * limit,
              triWave(elapsed * 0.49 + 0.31) * limit,
              triWave(elapsed * 0.55 + 0.67) * limit,
            )
            agentOrb.scale.setScalar(1 + 0.12 * Math.sin(elapsed * 10))
          }
        }
        break
      }
      case 'routing': {
        const router = this.accessory.getObjectByName('modelRouter')
        if (router) {
          const appear = smoothstep(stateElapsed / 280)
          router.scale.setScalar(Math.max(1e-4, appear))
          const nodesGrp = router.getObjectByName('routerNodes')
          const halo = router.getObjectByName('routerSelectHalo')
          if (nodesGrp) {
            nodesGrp.children.forEach((orb, idx) => {
              const isPicked = idx === routedModelIdx
              const slotX = (idx - 1) * 0.34
              const hoverY =
                Math.sin(elapsed * 4.5 + idx * 1.4) * 0.035 + (isPicked ? routingLock * 0.09 : 0)
              const forwardZ = isPicked ? routingLock * 0.14 : 0
              orb.position.set(slotX, hoverY, forwardZ)
              orb.scale.setScalar(isPicked ? 1 + routingLock * 0.35 : 0.85)
              if (orb instanceof THREE.Mesh) {
                orb.material.emissiveIntensity = isPicked ? 0.35 + routingLock * 0.65 : 0.2
              }
              if (isPicked) {
                activeRouterPos = new THREE.Vector3(
                  router.position.x + slotX,
                  router.position.y + hoverY,
                  router.position.z + forwardZ,
                )
                if (halo && halo instanceof THREE.Mesh) {
                  halo.position.copy(orb.position)
                  halo.scale.setScalar(0.8 + routingLock * 0.45 + Math.sin(elapsed * 10) * 0.05)
                  halo.material.color.setHex(orb.userData.colorHex)
                  halo.material.opacity = 0.35 + routingLock * 0.6
                }
              }
            })
          }
        }
        break
      }
      case 'rate-limited': {
        const battery = this.accessory.getObjectByName('cooldownBattery')
        if (battery && cooldown) {
          const appear = animate ? smoothstep(stateElapsed / 350) : 1
          popChild(battery, appear, 1 + cooldownReadyPop * 0.14)
          battery.rotation.z = Math.sin(elapsed * 2.2) * 0.03 - cooldownReadyPop * 0.12

          const liquid = battery.getObjectByName('cooldownLiquid')
          const innerCore = battery.getObjectByName('cooldownCore')
          const height = Math.max(0.012, 0.436 * cooldown.progress)
          liquid.scale.y = height
          liquid.position.y = -0.218 + height / 2

          if (innerCore) {
            innerCore.scale.y = Math.max(0.01, height * 0.96)
            innerCore.position.y = -0.218 + height / 2
          }

          const amplitude = animate && this.idle ? 0.008 * Math.sin(Math.PI * cooldown.progress) : 0
          const surface = (x, z) =>
            amplitude *
            (0.65 * Math.sin(stateElapsed / 420 + x * 11) +
              0.35 * Math.sin(stateElapsed / 610 + z * 14))
          const vertices = liquid.geometry.attributes.position
          const rest = liquid.userData.rest
          for (let i = 0; i < vertices.count; i++) {
            const x = rest[i * 3],
              y = rest[i * 3 + 1],
              z = rest[i * 3 + 2]
            vertices.setY(i, y + (y > 0 ? surface(x, z) / height : 0))
          }
          vertices.needsUpdate = true
          liquid.geometry.computeVertexNormals()
          liquid.geometry.computeBoundingSphere()

          const meniscus = battery.getObjectByName('cooldownMeniscus')
          meniscus.position.y = -0.218 + height
          const edgeVertices = meniscus.geometry.attributes.position
          const edgeRest = meniscus.userData.rest
          for (let i = 0; i < edgeVertices.count; i++) {
            edgeVertices.setY(
              i,
              edgeRest[i * 3 + 1] + surface(edgeRest[i * 3], edgeRest[i * 3 + 2]),
            )
          }
          edgeVertices.needsUpdate = true
          meniscus.geometry.computeVertexNormals()

          // Degradado continuo de 5 etapas como en la referencia (rojo -> naranja -> amarillo -> lima -> verde)
          const stops = [
            [0.0, new THREE.Color(0xef4444)],
            [0.25, new THREE.Color(0xf97316)],
            [0.5, new THREE.Color(0xeab308)],
            [0.75, new THREE.Color(0x84cc16)],
            [1.0, new THREE.Color(0x22c55e)],
          ]
          const p = cooldown.progress
          let liqColor = stops[0][1].clone()
          for (let s = 0; s < stops.length - 1; s++) {
            const [p0, c0] = stops[s]
            const [p1, c1] = stops[s + 1]
            if (p >= p0 && p <= p1) {
              liqColor.copy(c0).lerp(c1, (p - p0) / (p1 - p0))
              break
            }
          }
          liquid.material.color.copy(liqColor)
          liquid.material.emissive.copy(liqColor)
          liquid.material.emissiveIntensity =
            (0.48 + cooldown.progress * 0.25) * (cooldown.low ? cooldown.brightness : 1)

          const bolt = battery.getObjectByName('cooldownBolt')
          if (bolt) {
            popChild(bolt, cooldownReadyPop, 1 + 0.2 * Math.sin(elapsed * 18))
            bolt.position.y = 0.4 + cooldownReadyPop * 0.08
          }
        }
        break
      }
      case 'rolling-back': {
        const arrow = accChildren[0]
        if (arrow) {
          const appear = smoothstep(stateElapsed / 280)
          const bounce =
            appear > 0 ? 1 + 1.2 * Math.pow(appear - 1, 3) + 0.7 * Math.pow(appear - 1, 2) : 0
          popChild(arrow, appear, bounce)
          arrow.position.y = 1.06 + Math.sin(elapsed * 3.4) * 0.03
          const spinner = arrow.getObjectByName('rollbackSpinner')
          if (spinner) {
            spinner.rotation.z = animate && this.idle ? elapsed * 3.8 : 0
          }
        }
        break
      }
      case 'delegating':
        accChildren.forEach((drone, index) => {
          const launch = animate ? smoothstep(stateElapsed / 700) : 1
          const angle = (animate && this.idle ? stateElapsed / 1100 : 0) + index * Math.PI
          const dispatch = delegationDispatch(stateElapsed)
          const flight = animate && this.idle && index === dispatch.satellite ? dispatch.flight : 0
          drone.position.set(
            (index === 0 ? 1 : -1) * 1.3 * (1 - launch) + Math.cos(angle) * 1.48 * launch,
            0.42 + Math.sin(angle * 2) * 0.12 * launch + 0.1 * flight,
            Math.sin(angle) * 0.9 * launch + 0.88 * flight,
          )
          popChild(drone, launch, 1.22)
          drone.rotation.y = animate ? Math.sin(angle) * 0.25 : 0
          drone.rotation.x = -0.15 * flight
        })
        break
      case 'recalling': {
        const recallGrp = this.accessory.getObjectByName('recallGroup')
        if (recallGrp) {
          const appear = smoothstep(stateElapsed / 280)
          recallGrp.scale.setScalar(Math.max(1e-4, appear))

          const spark = recallGrp.getObjectByName('recallSpark')
          if (spark) {
            const eureka =
              recallCycle > 0.52 ? Math.sin(((recallCycle - 0.52) / 0.48) * Math.PI) : 0
            popChild(spark, eureka, 1.15 + 0.25 * eureka)
            spark.rotation.z = elapsed * 2.4
          }

          const nodesGrp = recallGrp.getObjectByName('recallNodes')
          if (nodesGrp) {
            nodesGrp.children.forEach((orb, idx) => {
              const ang = orb.userData.angle + elapsed * 1.65
              const converge =
                recallCycle > 0.45
                  ? smoothstep((recallCycle - 0.45) / 0.25) *
                    (1 - smoothstep((recallCycle - 0.8) / 0.2))
                  : 0
              const r = 0.34 * (1 - converge * 0.72)
              orb.position.set(
                0.68 + Math.cos(ang) * r,
                0.72 + Math.sin(ang) * r * 0.65,
                0.38 + Math.sin(ang * 2 + idx) * 0.08,
              )
              orb.rotation.x = elapsed * 2 + idx
              orb.rotation.y = elapsed * 2.5
              orb.scale.setScalar(0.85 + 0.3 * Math.sin(elapsed * 5 + idx))
            })
          }
        }
        break
      }
      case 'check-result': {
        const doc = this.accessory.getObjectByName('checkDoc')
        if (doc) {
          const appear = smoothstep(stateElapsed / 320)
          const bounce =
            appear > 0 ? 1 + 1.4 * Math.pow(appear - 1, 3) + 0.9 * Math.pow(appear - 1, 2) : 0
          popChild(doc, appear, bounce)
          doc.position.y = 0.62 + Math.sin(elapsed * 3.2) * 0.045
          doc.rotation.z = Math.sin(elapsed * 2.4) * 0.045

          const badge = doc.getObjectByName('docAlertBadge')
          if (badge) {
            badge.scale.setScalar(1 + 0.22 * Math.sin(elapsed * 8))
          }
        }
        break
      }
      case 'external-wait': {
        const clock = this.accessory.getObjectByName('extClock')
        if (clock) {
          const appear = smoothstep(stateElapsed / 320)
          popChild(clock, appear, 1.25)
          clock.position.y = 0.68 + Math.sin(elapsed * 2.8) * 0.03

          const hand = clock.getObjectByName('clockHand')
          if (hand) {
            hand.rotation.z = animate && this.idle ? -elapsed * 2.2 : 0
          }
          const pulse = clock.getObjectByName('extPulseRing')
          if (pulse && pulse instanceof THREE.Mesh) {
            const pT = (elapsed * 1.1) % 1
            pulse.scale.setScalar(0.85 + pT * 0.65)
            pulse.material.opacity = (1 - pT) * 0.75
          }
        }
        break
      }
      case 'listening': {
        const waves = this.accessory.getObjectByName('soundWaves')
        if (waves) {
          const appear = smoothstep(stateElapsed / 260)
          waves.scale.setScalar(Math.max(1e-4, appear))
          waves.children.forEach((arc) => {
            const { side, idx } = arc.userData
            const wavePhase = (elapsed * 1.8 - (2 - idx) * 0.28 + (side > 0 ? 0.14 : 0)) % 1
            const s = 1.22 - wavePhase * 0.34
            arc.scale.set(s, s, 1)
            if (arc instanceof THREE.Mesh) {
              arc.material.opacity = Math.sin(wavePhase * Math.PI) * 0.9 * appear
            }
          })
        }
        break
      }
      case 'no-results': {
        const emptyBadge = this.accessory.getObjectByName('emptySearchBadge')
        if (emptyBadge) {
          const reveal =
            noResPhase > 0.45
              ? smoothstep((noResPhase - 0.45) / 0.18) *
                (1 - smoothstep((noResPhase - 0.88) / 0.12))
              : 0
          popChild(emptyBadge, reveal, 1.15 + 0.08 * Math.sin(elapsed * 6))
          emptyBadge.position.y = 0.68 + Math.sin(elapsed * 3) * 0.035
        }
        break
      }
      case 'calm':
        accChildren.forEach((spark, i) => popChild(spark, curiosity * (0.8 + 0.2 * i)))
        break
      case 'ready':
        accChildren.forEach((dot, i) => {
          const pulse = loopFade * Math.sin(stateElapsed / 210 - i * 1.2)
          popChild(dot, 0.85 + 0.18 * pulse)
          dot.material.emissiveIntensity = 0.48 + 0.3 * pulse
        })
        break
      case 'sleeping':
        accChildren.forEach((letter, i) => {
          const age = stateElapsed - 200 - i * 1000
          const u = animate && this.idle ? (Math.max(age, 0) % 3000) / 3000 : 0
          if (animate && this.idle) {
            letter.position.set(
              0.46 + 0.6 * u + 0.1 * Math.sin(u * Math.PI * 4),
              0.43 + 1.05 * u,
              0.42 + 0.12 * Math.cos(u * Math.PI * 4),
            )
            const fade = smoothstep(u / 0.13) * (1 - smoothstep((u - 0.68) / 0.32))
            letter.children[0].material.opacity = fade
            popChild(letter, age > 0 ? 0.72 + 0.75 * u : 0)
          } else {
            letter.position.set(0.63 + i * 0.23, 0.85 + i * 0.23, 0.29)
            letter.children[0].material.opacity = 1
            popChild(letter, 1, letter.userData.scale)
          }
        })
        break
      case 'planning': {
        const dag = this.accessory.getObjectByName('dagGraph')
        if (dag) {
          const appear = smoothstep(stateElapsed / 320)
          dag.scale.setScalar(Math.max(1e-4, appear))
          dag.position.y = 0.36 + Math.sin(elapsed * 2.6) * 0.025

          const nodesGrp = dag.getObjectByName('dagNodes')
          if (nodesGrp) {
            nodesGrp.children.forEach((node, idx) => {
              const isCurrent = idx === dagStep
              const isDone = idx < dagStep
              const scale = isCurrent ? 1.38 + Math.sin(elapsed * 12) * 0.12 : isDone ? 1.08 : 0.9
              node.scale.setScalar(scale)
              if (node instanceof THREE.Mesh) {
                node.material.color.setHex(isCurrent ? 0x38bdf8 : isDone ? 0x818cf8 : 0xc7d2fe)
                node.material.emissive.setHex(isCurrent ? 0x0284c7 : 0x4f46e5)
                node.material.emissiveIntensity = isCurrent ? 0.85 : isDone ? 0.45 : 0.15
              }
              if (isCurrent) {
                activeDagNodePos = new THREE.Vector3(
                  dag.position.x + node.userData.basePos[0] * Math.cos(dag.rotation.y),
                  dag.position.y + node.userData.basePos[1],
                  dag.position.z - node.userData.basePos[0] * Math.sin(dag.rotation.y),
                )
              }
            })
          }

          const edgesGrp = dag.getObjectByName('dagEdges')
          if (edgesGrp) {
            edgesGrp.children.forEach((edge) => {
              const active = edge.userData.to <= dagStep
              if (edge instanceof THREE.Mesh) {
                edge.material.color.setHex(active ? 0x38bdf8 : 0x818cf8)
                edge.material.opacity = active ? 0.92 : 0.35
              }
            })
          }
        }
        break
      }
      case 'testing': {
        const tubesGrp = this.accessory.getObjectByName('testTubes')
        if (tubesGrp) {
          const appear = smoothstep(stateElapsed / 280)
          tubesGrp.scale.setScalar(Math.max(1e-4, appear))

          const amberCol = new THREE.Color(0xf59e0b)
          const amberEmissive = new THREE.Color(0xd97706)
          const greenCol = new THREE.Color(0x10b981)
          const greenEmissive = new THREE.Color(0x059669)

          tubesGrp.children.forEach((tube, idx) => {
            const wavePhase = elapsed * 4.2 - idx * 1.35
            const verticalBob = Math.sin(wavePhase) * 0.085
            tube.position.y = verticalBob
            tube.rotation.z = Math.cos(wavePhase) * 0.07

            const tubeProgress = clamp01((testCycle * 3.3 - idx) / 0.65)
            const passMix = smoothstep(tubeProgress)
            const popPulse =
              passMix > 0.2 && passMix < 0.98 ? Math.sin(passMix * Math.PI) * 0.14 : 0
            tube.scale.setScalar(1 + popPulse)

            const liquid = tube.getObjectByName('liquid')
            if (liquid && liquid instanceof THREE.Mesh) {
              liquid.material.color.copy(amberCol).lerp(greenCol, passMix)
              liquid.material.emissive.copy(amberEmissive).lerp(greenEmissive, passMix)
              liquid.material.emissiveIntensity = 0.45 + passMix * 0.35
            }

            const bubbles = tube.getObjectByName('tubeBubbles')
            if (bubbles) {
              bubbles.children.forEach((bub) => {
                const bCycle = (elapsed * (1.5 + passMix * 0.9) + bub.userData.phase) % 1
                bub.position.set(
                  bub.userData.xOff + Math.sin(bCycle * Math.PI * 2) * 0.014,
                  -0.19 + bCycle * 0.24,
                  0.02,
                )
                bub.scale.setScalar(Math.sin(bCycle * Math.PI) * (0.85 + passMix * 0.45))
              })
            }
          })
        }
        break
      }
      case 'learning': {
        const orbit = this.accessory.getObjectByName('memoryOrbit')
        if (orbit) {
          const appear = smoothstep(stateElapsed / 320)
          orbit.scale.setScalar(Math.max(1e-4, appear))

          const orbs = orbit.getObjectByName('memoryOrbs')
          if (orbs) {
            orbs.children.forEach((orb, idx) => {
              const cycle = (elapsed * 0.26 + orb.userData.phase) % 1
              const spiral = smoothstep(cycle)
              const angle = orb.userData.baseAngle + elapsed * 1.15 + spiral * Math.PI * 1.8
              const radius = 1.12 * (1 - spiral * 0.88)
              const yPos =
                0.76 * (1 - spiral) -
                0.08 * spiral +
                Math.sin(angle * 2 + idx) * 0.045 * (1 - spiral)

              orb.position.set(Math.cos(angle) * radius, yPos, Math.sin(angle) * radius * 0.78)
              orb.rotation.x = elapsed * 1.5 + idx
              orb.rotation.y = elapsed * 1.9

              const fade = smoothstep(cycle / 0.14) * (1 - smoothstep((cycle - 0.76) / 0.24))
              const s = Math.max(1e-4, (1 - spiral * 0.45) * fade * appear)
              orb.scale.setScalar(s)
              if (orb instanceof THREE.Mesh) {
                orb.material.opacity = fade * 0.94
              }
            })
          }
        }
        break
      }
      case 'merging': {
        const mg = this.accessory.getObjectByName('gitMerge')
        if (mg) {
          const appear = smoothstep(stateElapsed / 260)
          mg.scale.setScalar(Math.max(1e-4, appear))
          const cycle = mergeCycle
          const conv = mergeConv
          const spread = 0.56 * (1 - conv)

          const lNode = mg.getObjectByName('mergeLeft')
          const rNode = mg.getObjectByName('mergeRight')
          const cNode = mg.getObjectByName('mergeCenter')
          const lTrail = mg.getObjectByName('leftTrail')
          const rTrail = mg.getObjectByName('rightTrail')
          const flash = mg.getObjectByName('mergeFlash')

          if (lNode && rNode) {
            lNode.position.set(-spread, 0, 0)
            rNode.position.set(spread, 0, 0)
            const sideScale = 1 - conv * 0.45
            lNode.scale.setScalar(Math.max(1e-4, sideScale))
            rNode.scale.setScalar(Math.max(1e-4, sideScale))
          }
          if (lTrail && rTrail) {
            const tScale = Math.max(1e-4, 1 - conv)
            lTrail.position.set(-spread * 0.5, 0, -0.01)
            lTrail.rotation.z = Math.PI / 2
            lTrail.scale.set(1, tScale, 1)

            rTrail.position.set(spread * 0.5, 0, -0.01)
            rTrail.rotation.z = Math.PI / 2
            rTrail.scale.set(1, tScale, 1)
          }
          if (cNode) {
            const pop = conv > 0.75 ? smoothstep((conv - 0.75) / 0.25) : 0
            const pulse = pop > 0 ? 1 + 0.25 * Math.sin(elapsed * 10) : 0.001
            cNode.visible = pop > 0
            cNode.scale.setScalar(Math.max(1e-4, pop * pulse))
          }
          if (flash && flash instanceof THREE.Mesh) {
            const fT = cycle > 0.55 ? (cycle - 0.55) / 0.45 : 0
            flash.visible = fT > 0
            flash.scale.setScalar(0.5 + fT * 1.45)
            flash.material.opacity = (1 - fT) * 0.85
          }
        }
        break
      }
      case 'error':
        accChildren.forEach((c, idx) => {
          if (c.name === 'warnSign') {
            const appear = smoothstep(stateElapsed / 260)
            const bob = Math.sin(elapsed * 8) * 0.045
            const pulse = 1 + 0.15 * Math.sin(elapsed * 12)
            c.position.y = 1.04 + bob
            c.rotation.z = Math.sin(elapsed * 18) * 0.08
            popChild(c, appear, 1.2 * pulse)
          } else {
            const entryT = smoothstep((stateElapsed - idx * 60) / 260)
            const jag = Math.sin(elapsed * 22 + idx * 1.7)
            popChild(c, entryT, 1 + 0.22 * jag)
            c.position.x = c.userData.baseX + c.userData.side * jag * 0.035
            c.position.y = c.userData.baseY + jag * 0.025
          }
        })
        if (this.errorEyes && dt >= 180) this.errorEyes = false
        break
      case 'waiting': {
        const badge = accChildren[0]
        if (badge) {
          const ring = badge.getObjectByName('alertPulseRing')
          if (ring) {
            const waveT = (elapsed * 1.4) % 1
            ring.scale.setScalar(1 + waveT * 0.85)
            if (ring instanceof THREE.Mesh) ring.material.opacity = (1 - waveT) * 0.75
          }
        }
        break
      }
      case 'paused': {
        const badge = accChildren[0]
        if (badge) {
          const spinner = badge.getObjectByName('pauseSpinner')
          if (spinner) spinner.rotation.z = -elapsed * 1.1
        }
        break
      }
      case 'offline': {
        const badge = accChildren[0]
        if (badge) {
          const arcs = badge.getObjectByName('wifiArcs')
          if (arcs) {
            const step = Math.floor(elapsed * 2.6) % 4
            arcs.children.forEach((arcMesh, i) => {
              const active = i < step
              if (arcMesh instanceof THREE.Mesh) {
                arcMesh.material.color.setHex(active ? 0x94a3b8 : 0x475569)
              }
              arcMesh.scale.setScalar(active ? 1.04 : 0.94)
            })
          }
          const slash = badge.getObjectByName('offlineSlash')
          if (slash && slash instanceof THREE.Mesh) {
            slash.material.opacity = 0.55 + 0.45 * Math.sin(elapsed * 7)
          }
        }
        break
      }
      case 'cancelled': {
        const badge = accChildren[0]
        if (badge) {
          const appear = smoothstep(stateElapsed / 280)
          const bounce =
            appear > 0 ? 1 + 1.8 * Math.pow(appear - 1, 3) + 1.2 * Math.pow(appear - 1, 2) : 0
          popChild(badge, appear, BADGE_SCALE['cancel-x'] * bounce)
          badge.position.set(0.86, 0.56 + Math.sin(elapsed * 2.5) * 0.035, 0.38)
          badge.rotation.y = Math.sin(elapsed * 2) * 0.22
        }
        break
      }
      case 'running':
        accChildren.forEach((c, idx) => {
          const env = smoothstep((stateElapsed - 260 - idx * 180) / 280)
          c.visible = env > 0
          const streakPulse =
            this.gestureAt !== null
              ? Math.pow(Math.sin(Math.PI * clamp01((dt - 260 - idx * 85) / 680)), 2)
              : 0.5 + 0.5 * Math.sin(elapsed * 14 - idx * 1.6)
          c.scale.x = Math.max(env, 1e-4) * (1 + 0.45 * streakPulse)
          c.position.x = -1.35 - idx * 0.1 - 0.18 * streakPulse * env
        })
        break
      case 'thinking':
        accChildren.forEach((c, idx) => {
          const entryT = smoothstep((stateElapsed - idx * 230) / 280)
          const phase = elapsed * 2.1 - idx * 0.9
          const floatY = loopFade * Math.sin(phase) * 0.045
          const floatX = loopFade * Math.cos(phase * 0.85) * 0.015
          const pulse = 1 + loopFade * Math.sin(phase) * 0.06
          popChild(c, entryT, pulse)
          const baseXs = [0.72, 0.88, 1.12]
          const baseYs = [0.72, 0.98, 1.34]
          c.position.x = baseXs[idx] + floatX
          c.position.y = baseYs[idx] + floatY
          if (idx === 2) {
            c.material.emissiveIntensity =
              0.48 + 0.45 * loopFade * (0.5 + 0.5 * Math.sin(elapsed * 2.4))
          }
        })
        break
      case 'coding': {
        const kb = this.accessory.getObjectByName('holoKeyboard')
        if (kb) {
          const appear = smoothstep(stateElapsed / 280)
          kb.scale.setScalar(Math.max(1e-4, appear))
          kb.position.y = -0.68 + Math.sin(elapsed * 4) * 0.015
          const keysGrp = kb.getObjectByName('keys')
          if (keysGrp) {
            keysGrp.children.forEach((kMesh, idx) => {
              const press = Math.max(0, Math.sin(elapsed * 22 + idx * 1.9))
              kMesh.position.y = 0.024 - press * 0.018
            })
          }
        }
        break
      }
      case 'deploying': {
        accChildren.forEach((ring, idx) => {
          const cycle = (elapsed * 2.4 + idx * 0.33) % 1
          const appear = smoothstep(stateElapsed / 220)
          ring.visible = appear > 0
          ring.position.y = -0.68 - cycle * 0.65
          const s = (1 - cycle * 0.45) * appear
          ring.scale.set(s, s, 1)
          if (ring instanceof THREE.Mesh) ring.material.opacity = (1 - cycle) * 0.85 * appear
        })
        break
      }
      case 'celebrating': {
        accChildren.forEach((star, idx) => {
          const appear = smoothstep((stateElapsed - idx * 60) / 280)
          const angle = star.userData.angle + elapsed * 2.2
          const radius = 1.18 + Math.sin(elapsed * 4 + idx) * 0.1
          star.position.set(
            Math.cos(angle) * radius,
            star.userData.yBase + Math.sin(elapsed * 5 + idx * 1.2) * 0.14,
            Math.sin(angle) * 0.45 + 0.15,
          )
          star.rotation.z = elapsed * 3 * (idx % 2 === 0 ? 1 : -1)
          star.rotation.y = elapsed * 2
          popChild(star, appear, 0.85 + 0.2 * Math.sin(elapsed * 6 + idx))
        })
        break
      }
      case 'auditing': {
        const beam = this.accessory.getObjectByName('scanBeam')
        if (beam) {
          const appear = smoothstep(stateElapsed / 300)
          beam.scale.setScalar(Math.max(1e-4, appear))
          const laser = beam.getObjectByName('laserLine')
          if (laser) {
            const radius = 0.49
            const yPos = Math.sin(elapsed * 4.8) * (radius * 0.92)
            const chordWidth = 2 * Math.sqrt(Math.max(0.01, radius * radius - yPos * yPos))
            laser.position.y = yPos
            laser.scale.x = chordWidth
          }
        }
        break
      }
      case 'overloaded': {
        accChildren.forEach((drop) => {
          const cycle = (elapsed * 1.45 + drop.userData.phase) % 1
          const appear = smoothstep(stateElapsed / 250)
          drop.visible = appear > 0
          drop.position.y = drop.userData.y - cycle * 0.48
          const s = Math.sin(cycle * Math.PI) * drop.userData.s * appear
          drop.scale.setScalar(Math.max(1e-4, s))
        })
        break
      }
    }

    // Movimiento horizontal y profundidad
    if (state === 'guardrail' && animate) {
      const snapRecoil = smoothstep(stateElapsed / 160)
      const impactCycle = (stateElapsed % 2400) / 2400
      const pulseRecoil = impactCycle < 0.25 ? Math.sin((impactCycle / 0.25) * Math.PI) : 0
      root.position.x = Math.sin(elapsed * 28) * 0.015 * pulseRecoil
      root.position.z = (-0.22 - pulseRecoil * 0.08) * snapRecoil
    } else if (state === 'rolling-back' && animate) {
      const backIn = smoothstep(stateElapsed / 420)
      const stepWobble = Math.sin(elapsed * 5.2) * 0.085 * backIn
      root.position.x = stepWobble
      root.position.z = (-0.28 + Math.cos(elapsed * 5.2) * 0.04) * backIn
    } else {
      root.position.z = 0
      if (isRunning) {
        const s = (a, b) => smoothstep((stateElapsed - a) / (b - a))
        const entryX = -0.18 * s(0, 260) + 0.58 * s(260, 620) - 0.4 * s(670, 1250)
        const loopX = Math.sin(elapsed * 5) * 0.06 * loopFade
        root.position.x = entryX + loopX
      } else if (state === 'error') {
        const t = clamp01(stateElapsed / 680)
        const entryShake = 0.095 * Math.sin(t * 6 * Math.PI) * Math.pow(Math.sin(Math.PI * t), 2)
        const glitchPhase = elapsed % 2.2
        const glitch = glitchPhase > 1.85 && animate ? Math.sin(elapsed * 55) * 0.035 : 0
        root.position.x = entryShake + glitch
      } else if (state === 'celebrating' && animate) {
        root.position.x = Math.sin(elapsed * 4.5) * 0.16 * activeFade
      } else if (state === 'overloaded' && animate) {
        root.position.x = Math.sin(elapsed * 32) * 0.025 * activeFade
      } else if (state === 'offline' && animate) {
        root.position.x = Math.sin(elapsed * 1.7) * 0.07 * activeFade
      } else {
        root.position.x = 0
      }
    }

    // Inclinación Dinámica del Cuerpo y Visor
    this.gaze[0] += (this.aim[0] - this.gaze[0]) * lerpRate
    this.gaze[1] += (this.aim[1] - this.gaze[1]) * lerpRate

    const ptrNormX = this.pointerGaze[0] / 48
    const ptrNormY = this.pointerGaze[1] / 34

    const guardrailPitch =
      state === 'guardrail' && animate
        ? (-0.15 -
            (stateElapsed % 2400 < 600
              ? Math.sin(((stateElapsed % 2400) / 600) * Math.PI) * 0.07
              : 0)) *
          activeFade
        : 0
    const cooldownPitch =
      state === 'rate-limited' && animate
        ? (0.14 * (1 - cooldown.progress) +
            cooldownSigh * 0.07 -
            cooldownGlance * 0.12 -
            cooldownReadyPop * 0.18) *
          activeFade
        : 0
    const benchPitch =
      state === 'benchmarking' && animate
        ? (0.09 + Math.sin(elapsed * 3.4) * 0.035) * activeFade
        : 0
    const rollbackPitch =
      state === 'rolling-back' && animate
        ? (-0.17 + Math.sin(elapsed * 5.2) * 0.04) * activeFade
        : 0
    const sandboxPitch =
      state === 'sandbox' && animate ? (0.08 + Math.sin(elapsed * 2.8) * 0.04) * activeFade : 0
    const routingPitch =
      state === 'routing' && animate ? (0.1 + Math.sin(elapsed * 3.6) * 0.03) * activeFade : 0
    const recallPitch =
      state === 'recalling' && animate
        ? (-0.12 * Math.sin(recallCycle * Math.PI) +
            (recallCycle > 0.55 ? 0.08 * Math.sin(((recallCycle - 0.55) / 0.45) * Math.PI) : 0)) *
          activeFade
        : 0
    const checkPitch =
      state === 'check-result' && animate
        ? (0.06 + Math.sin(checkCycle * Math.PI * 2) * 0.06) * activeFade
        : 0
    const listenPitch =
      state === 'listening' && animate ? (0.14 + Math.sin(elapsed * 4.2) * 0.035) * activeFade : 0
    const noResPitch =
      state === 'no-results' && animate
        ? (noResPhase > 0.5 ? 0.12 * Math.sin(((noResPhase - 0.5) / 0.5) * Math.PI) : 0) *
          activeFade
        : 0

    const extraPitch =
      guardrailPitch +
      cooldownPitch +
      benchPitch +
      rollbackPitch +
      sandboxPitch +
      routingPitch +
      recallPitch +
      checkPitch +
      listenPitch +
      noResPitch +
      (state === 'coding'
        ? 0.16 * activeFade
        : state === 'testing'
          ? 0.18 * activeFade
          : state === 'planning'
            ? -0.06 * activeFade
            : state === 'healing'
              ? 0.06 * activeFade
              : state === 'learning'
                ? -0.08 * activeFade
                : state === 'deploying'
                  ? -0.22 * activeFade
                  : state === 'overloaded'
                    ? 0.18 * activeFade
                    : state === 'paused'
                      ? 0.09 * activeFade
                      : state === 'cancelled'
                        ? 0.15 * smoothstep(stateElapsed / 450)
                        : state === 'waiting'
                          ? -0.07 * activeFade
                          : state === 'auditing'
                            ? 0.08 * Math.sin(elapsed * 5.6) * activeFade
                            : 0)

    const pitch =
      runEnv * 0.19 -
      thinkEnv * 0.1 +
      extraPitch +
      (state === 'sleeping'
        ? (gestureEnv + 0.5 * loopFade) * 0.11
        : state === 'offline'
          ? Math.sin(elapsed * 2.2) * 0.08 * activeFade
          : 0)

    const cooldownYaw = state === 'rate-limited' && animate ? cooldownGlance * 0.28 * activeFade : 0
    const benchYaw = state === 'benchmarking' && animate ? -benchArmTilt * 0.65 * activeFade : 0
    const rollbackYaw =
      state === 'rolling-back' && animate ? Math.sin(elapsed * 2.6) * 0.22 * activeFade : 0
    const sandboxYaw =
      state === 'sandbox' && animate ? Math.sin(elapsed * 2.2) * 0.16 * activeFade : 0
    const routingYaw =
      state === 'routing' && animate && activeRouterPos ? activeRouterPos.x * 0.42 * activeFade : 0
    const recallYaw =
      state === 'recalling' && animate ? 0.18 * Math.sin(recallCycle * Math.PI) * activeFade : 0
    const checkYaw =
      state === 'check-result' && animate
        ? (0.14 + Math.sin(checkCycle * Math.PI * 2) * 0.14) * activeFade
        : 0
    const extWaitYaw =
      state === 'external-wait' && animate ? Math.sin(elapsed * 1.9) * 0.14 * activeFade : 0
    const listenYaw =
      state === 'listening' && animate ? (-0.12 + Math.sin(elapsed * 2.6) * 0.05) * activeFade : 0
    const noResYaw =
      state === 'no-results' && animate
        ? (noResPhase < 0.5 ? Math.sin((noResPhase / 0.5) * Math.PI * 2) * 0.24 : 0) * activeFade
        : 0

    const cancelShake =
      state === 'cancelled'
        ? Math.sin(clamp01(stateElapsed / 750) * Math.PI * 4) *
          (1 - clamp01(stateElapsed / 750)) *
          0.28
        : 0
    const offlineSearchYaw =
      state === 'offline' && animate ? Math.sin(elapsed * 1.7) * 0.26 * activeFade : 0
    const waitingYaw =
      state === 'waiting' && animate ? Math.sin(elapsed * 3) * 0.08 * activeFade : 0
    const planningYaw = state === 'planning' && activeDagNodePos ? activeDagNodePos.x * 0.25 : 0
    const testingYaw =
      state === 'testing' && animate ? (this.smoothTestIdx - 1) * 0.14 * activeFade : 0
    const healingYaw = state === 'healing' && animate ? 0.16 * activeFade : 0
    const pokeSpinYaw = pokeEnv > 0 && this.pokeCount === 0 ? smoothstep(pokeT) * Math.PI * 2 : 0
    const dizzyYaw = dizzyEnv * Math.sin(elapsed * 11) * 0.34
    const extraYaw =
      cooldownYaw +
      benchYaw +
      rollbackYaw +
      sandboxYaw +
      routingYaw +
      recallYaw +
      checkYaw +
      extWaitYaw +
      listenYaw +
      noResYaw +
      (state === 'celebrating' && animate ? Math.sin(elapsed * 4.5) * 0.25 * activeFade : 0) +
      cancelShake +
      offlineSearchYaw +
      waitingYaw +
      planningYaw +
      testingYaw +
      healingYaw +
      pokeSpinYaw +
      dizzyYaw

    const targetBodyRotX =
      pitch -
      0.07 * sleepBreath +
      juggleEnv * 0.22 -
      stretchEnv * 0.14 +
      this.gaze[1] * 0.14 +
      ptrNormY * 0.12
    const targetBodyRotY = reviewEnv * 0.14 + extraYaw + this.gaze[0] * 0.24 + ptrNormX * 0.18

    root.rotation.x += (targetBodyRotX - root.rotation.x) * (animate ? 0.16 : 1)
    root.rotation.y += (targetBodyRotY - root.rotation.y) * (animate ? 0.16 : 1)

    const combineX = clamp01(Math.abs(this.gaze[0] + ptrNormX)) * Math.sign(this.gaze[0] + ptrNormX)
    const combineY = clamp01(Math.abs(this.gaze[1] + ptrNormY)) * Math.sign(this.gaze[1] + ptrNormY)
    const radialMag = Math.hypot(combineX, combineY)

    const targetVisorRotX = -0.14 + pitch * 0.25 + combineY * 0.14
    const targetVisorRotY =
      reviewEnv * 0.12 +
      (planningYaw +
        testingYaw +
        healingYaw +
        checkYaw +
        noResYaw +
        routingYaw +
        sandboxYaw +
        rollbackYaw +
        benchYaw +
        cooldownYaw) *
        0.45 +
      combineX * 0.16
    const targetVisorPosX = combineX * 0.042 + reviewEnv * 0.025
    const targetVisorPosY = -0.04 - combineY * 0.032
    const targetVisorPosZ =
      0.515 +
      (state === 'reviewing' || state === 'auditing' ? Math.abs(reviewEnv) * 0.04 : 0) +
      radialMag * 0.032

    const visorLerp = animate ? 0.26 : 1
    visor.rotation.x += (targetVisorRotX - visor.rotation.x) * visorLerp
    visor.rotation.y += (targetVisorRotY - visor.rotation.y) * visorLerp
    visor.position.x += (targetVisorPosX - visor.position.x) * visorLerp
    visor.position.y += (targetVisorPosY - visor.position.y) * visorLerp
    visor.position.z += (targetVisorPosZ - visor.position.z) * visorLerp

    // Cálculo de objetivos para las manos
    const [handL, handR] = hands
    const targetL = new THREE.Vector3(...HAND_REST_POS[0])
    const targetR = new THREE.Vector3(
      HAND_REST_POS[1][0] - reviewActive * 0.14 + reviewScanX * 0.08,
      HAND_REST_POS[1][1] + reviewActive * 0.12 + reviewScanY * 0.14,
      HAND_REST_POS[1][2] + reviewActive * 0.34,
    )

    // Coreografías específicas de manos para cada estado
    if (state === 'guardrail' && animate) {
      const snapHands = smoothstep(stateElapsed / 180)
      const impactCycle = (stateElapsed % 2400) / 2400
      const brace = impactCycle < 0.25 ? Math.sin((impactCycle / 0.25) * Math.PI) * 0.06 : 0
      targetL.set(-0.46, 0.06 + brace, 0.76 - brace)
      targetR.set(0.46, 0.06 + brace, 0.76 - brace)
      if (snapHands < 1) {
        targetL.lerp(new THREE.Vector3(...HAND_REST_POS[0]), 1 - snapHands)
        targetR.lerp(new THREE.Vector3(...HAND_REST_POS[1]), 1 - snapHands)
      }
    } else if (state === 'benchmarking' && animate) {
      const weighDelta = benchArmTilt * 0.42
      targetL.set(-0.68, -0.22 - weighDelta, 0.58)
      targetR.set(0.68, -0.22 + weighDelta, 0.58)
    } else if (state === 'rolling-back' && animate) {
      const revAngle = elapsed * 5.6
      const rWheel = 0.15
      targetL.set(-0.78, -0.22 + Math.sin(revAngle) * rWheel, 0.24 - Math.cos(revAngle) * rWheel)
      targetR.set(
        0.78,
        -0.22 + Math.sin(revAngle + Math.PI) * rWheel,
        0.24 - Math.cos(revAngle + Math.PI) * rWheel,
      )
    } else if (state === 'sandbox' && animate) {
      const tapL = Math.pow(Math.max(0, Math.sin(elapsed * 4.2)), 3) * 0.1
      const tapR = Math.pow(Math.max(0, Math.sin(elapsed * 4.2 + Math.PI)), 3) * 0.1
      targetL.set(-0.56 + tapL, -0.08 + Math.sin(elapsed * 2.1) * 0.06, 0.72)
      targetR.set(0.56 - tapR, -0.08 - Math.sin(elapsed * 2.1) * 0.06, 0.72)
    } else if (state === 'routing' && animate) {
      const weigh = Math.sin(elapsed * 4.5) * 0.06
      const pickX = activeRouterPos ? activeRouterPos.x : 0
      targetL.set(-0.68, -0.34 + weigh, 0.44)
      targetR.set(0.56 + pickX * 0.35, -0.22 + routingLock * 0.14, 0.62)
    } else if (state === 'recalling' && animate) {
      const eurekaLift = recallCycle > 0.5 ? Math.sin(((recallCycle - 0.5) / 0.5) * Math.PI) : 0
      targetL.set(-0.46, -0.44 + Math.sin(elapsed * 2.4) * 0.02, 0.56)
      targetR.set(0.76, 0.12 + eurekaLift * 0.24, 0.32)
    } else if (state === 'check-result' && animate) {
      const gestureBeat = Math.sin(checkCycle * Math.PI * 2)
      targetL.set(-0.84, -0.38, 0.24)
      targetR.set(
        1.02 + gestureBeat * 0.04,
        -0.06 + Math.abs(gestureBeat) * 0.06,
        0.36 + gestureBeat * 0.04,
      )
    } else if (state === 'external-wait' && animate) {
      const altBeat = Math.sin(elapsed * 3.8)
      targetL.set(-0.84, -0.34 + Math.max(0, altBeat) * 0.14, 0.32)
      targetR.set(0.98 + Math.cos(elapsed * 3.8) * 0.04, -0.1 + Math.max(0, -altBeat) * 0.06, 0.34)
    } else if (state === 'listening' && animate) {
      const pulseHand = Math.sin(elapsed * 6) * 0.03
      targetL.set(-0.78, -0.36 + pulseHand, 0.32)
      targetR.set(0.84, 0.05 + pulseHand * 1.4, 0.36)
    } else if (state === 'no-results' && animate) {
      const shrug = noResPhase > 0.48 ? Math.sin(((noResPhase - 0.48) / 0.52) * Math.PI) : 0
      targetL.set(-0.88 - 0.16 * shrug, -0.42 + 0.3 * shrug, 0.12 + 0.24 * shrug)
      targetR.set(0.88 + 0.16 * shrug, -0.42 + 0.3 * shrug, 0.12 + 0.24 * shrug)
    } else if (state === 'planning' && animate) {
      targetL.set(-0.56, -0.34, 0.48)
      if (activeDagNodePos) {
        targetR.set(activeDagNodePos.x + 0.22, activeDagNodePos.y - 0.11, activeDagNodePos.z - 0.32)
      } else {
        targetR.set(0.56, 0.24, 0.76)
      }
    } else if (state === 'testing' && animate) {
      const bobL = Math.sin(elapsed * 5) * 0.04
      const bobR = Math.cos(elapsed * 5) * 0.04
      targetL.set(-0.62, -0.24 + bobL, 0.64)
      targetR.set(0.62, -0.24 + bobR, 0.64)
    } else if (state === 'healing' && animate) {
      const crank = Math.sin(elapsed * 7.5)
      const wrenchAngle = 0.44 + (crank > 0 ? crank * 0.36 : crank * 0.1)
      const gripDist = 0.44
      const nutX = 0.96,
        nutY = 0.34,
        nutZ = 0.42
      targetL.set(-0.84, -0.36 + Math.sin(elapsed * 4) * 0.04, 0.24)
      targetR.set(
        nutX + Math.sin(wrenchAngle) * gripDist,
        nutY - Math.cos(wrenchAngle) * gripDist,
        nutZ - 0.22,
      )
    } else if (state === 'learning' && animate) {
      const zenFloat = Math.sin(elapsed * 2.5) * 0.035
      targetL.set(-0.62, -0.28 + zenFloat, 0.56)
      targetR.set(0.62, -0.28 + zenFloat, 0.56)
    } else if (state === 'merging' && animate) {
      const handX = 0.86 - mergeConv * 0.52
      const handZ = 0.42 + mergeConv * 0.26
      targetL.set(-handX, -0.22, handZ)
      targetR.set(handX, -0.22, handZ)
    } else if (isRunning && animate) {
      const swing = Math.sin(elapsed * 10) * 0.2 * Math.min(1, stateElapsed / 280)
      const bob = Math.abs(Math.cos(elapsed * 10)) * 0.065 * Math.min(1, stateElapsed / 280)
      targetL.z += swing
      targetL.y += bob
      targetR.z -= swing
      targetR.y += bob
    } else if (state === 'coding' && animate) {
      const typeL = Math.sin(elapsed * 24) * 0.09
      const typeR = Math.cos(elapsed * 24) * 0.09
      const sweep = Math.sin(elapsed * 5) * 0.11
      targetL.set(-0.34 + sweep, -0.38 + typeL, 0.72)
      targetR.set(0.34 + sweep, -0.38 + typeR, 0.72)
    } else if (state === 'deploying' && animate) {
      targetL.set(-0.76, -0.58, -0.05)
      targetR.set(0.76, -0.58, -0.05)
    } else if (state === 'celebrating' && animate) {
      const beat = elapsed * 9
      targetL.set(-0.82 - Math.cos(beat) * 0.12, 0.18 + Math.sin(beat) * 0.32, 0.32)
      targetR.set(0.82 + Math.cos(beat) * 0.12, 0.18 - Math.sin(beat) * 0.32, 0.32)
    } else if (state === 'auditing' && animate) {
      targetL.set(-0.46, -0.34, 0.56)
      targetR.set(0.84, -0.18 + Math.sin(elapsed * 3) * 0.06, 0.42)
    } else if (state === 'overloaded' && animate) {
      targetL.set(-0.82, -0.22 + Math.sin(elapsed * 16) * 0.08, 0.38)
      targetR.set(0.76 + Math.sin(elapsed * 18) * 0.14, -0.12 + Math.cos(elapsed * 18) * 0.14, 0.52)
    } else if (state === 'error' && animate) {
      const panicShake = Math.sin(elapsed * 26) * 0.03
      targetL.set(-0.74, 0.22 + panicShake, 0.36)
      targetR.set(0.74, 0.22 - panicShake, 0.36)
    } else if (state === 'waiting' && animate) {
      const waveHand = Math.sin(elapsed * 8.5)
      targetL.set(-0.86 + waveHand * 0.14, 0.26 + Math.abs(waveHand) * 0.08, 0.44)
    } else if (state === 'paused' && animate) {
      const tap = Math.max(0, Math.sin(elapsed * 5.5)) * 0.07
      targetL.set(-0.46, -0.36 + tap, 0.52)
    } else if (state === 'offline' && animate) {
      const knock = Math.pow(Math.max(0, Math.sin(elapsed * 4.2)), 4) * 0.12
      targetL.set(-0.86 + knock, 0.05, 0.32)
    } else if (state === 'cancelled') {
      const crossT = smoothstep(stateElapsed / 380)
      targetL.lerp(new THREE.Vector3(0.2, -0.22, 0.68), crossT)
      targetR.lerp(new THREE.Vector3(-0.2, -0.28, 0.74), crossT)
    }

    const badgeScale =
      cfg.accessory && cfg.accessory !== 'cancel-x' ? BADGE_SCALE[cfg.accessory] : undefined
    if (badgeScale) {
      const lift = easeOutCubic(stateElapsed)
      const waveWait = state === 'waiting' ? Math.sin(elapsed * 4.5) * 0.06 * loopFade : 0
      const pauseFloat = state === 'paused' ? Math.sin(elapsed * 2.2) * 0.035 : 0
      targetR.y += 0.9 * lift + waveWait + pauseFloat
      targetR.z += 0.14 * lift
      const pop = clamp01((stateElapsed - 320) / 320)
      const bounce = pop > 0 ? 1 + 2.5 * Math.pow(pop - 1, 3) + 1.5 * Math.pow(pop - 1, 2) : 0
      popChild(accChildren[0], +(pop > 0), badgeScale * bounce)
    }

    if (state === 'calm') {
      const breath = Math.sin(elapsed * 2) * 0.025 * loopFade
      targetL.x -= breath
      targetR.x += breath
      targetR.y += 0.16 * curiosity
      if (juggleEnv > 0) {
        const jL = Math.abs(Math.sin(elapsed * 7.5)) * 0.22 * juggleEnv
        const jR = Math.abs(Math.cos(elapsed * 7.5)) * 0.22 * juggleEnv
        targetL.set(-0.46, -0.32 + jL, 0.56)
        targetR.set(0.46, -0.32 + jR, 0.56)
      } else if (stretchEnv > 0) {
        targetL.set(-0.74, 0.42 * stretchEnv - 0.15 * (1 - stretchEnv), 0.24)
        targetR.set(0.74, 0.42 * stretchEnv - 0.15 * (1 - stretchEnv), 0.24)
      }
    } else if (state === 'sleeping') {
      targetL.y -= 0.08 + 0.035 * sleepBreath
      targetR.y -= 0.08 + 0.035 * sleepBreath
    } else if (state === 'ready') {
      targetL.x += 0.09
      targetR.x -= 0.09
      targetL.y += 0.04 * readySway
      targetR.y -= 0.04 * readySway
    } else if (state === 'thinking') {
      const chin = animate ? smoothstep(stateElapsed / 620) : 1
      targetR.lerp(new THREE.Vector3(0.31, -0.56, 0.61), chin)
      targetL.y += 0.028 * Math.sin(elapsed * 2.4) * loopFade
    }

    if (state === 'delegating') {
      const lift = animate ? smoothstep(stateElapsed / 500) : 1
      const beat = animate && this.idle ? Math.sin(stateElapsed / 260) : 0
      targetL.y += (0.22 + 0.075 * beat) * lift
      targetR.y += (0.22 - 0.075 * beat) * lift
      targetL.x -= 0.035 * beat * lift
      targetR.x += 0.035 * beat * lift
      targetL.z += 0.12 * lift
      targetR.z += 0.12 * lift
      if (animate && this.idle) {
        const dispatch = delegationDispatch(stateElapsed)
        const hand = dispatch.satellite === 0 ? targetR : targetL
        hand.z += 0.23 * dispatch.cue
        hand.y += 0.09 * dispatch.cue
      }
    }

    if (state === 'rate-limited' && cooldown) {
      const sprintTime = cooldown.tCycle - 7850
      const moving = animate && this.idle
      const charging = moving ? 1 - smoothstep(sprintTime / 180) : 0
      const sprint = moving
        ? smoothstep(sprintTime / 180) * (1 - smoothstep((sprintTime - 550) / 400))
        : 0
      const pace = moving ? Math.sin(cooldown.tCycle / (sprintTime < 0 ? 310 : 95)) : 0
      const swing = pace * (0.055 * charging + 0.19 * sprint)
      const droop = (1 - cooldown.progress) * 0.14
      targetL.z += swing
      targetR.z -= swing
      targetL.y +=
        Math.max(0, pace) * (0.02 * charging + 0.055 * sprint) -
        droop -
        0.025 * rechargeBreath -
        cooldownSigh * 0.04
      targetR.y +=
        Math.max(0, -pace) * (0.02 * charging + 0.055 * sprint) -
        droop -
        0.025 * rechargeBreath -
        cooldownSigh * 0.04
      targetL.x -= droop * 0.35
      targetR.x += droop * 0.35
      if (cooldownReadyPop > 0) {
        targetL.y += cooldownReadyPop * 0.42
        targetR.y += cooldownReadyPop * 0.42
        targetL.z += cooldownReadyPop * 0.16
        targetR.z += cooldownReadyPop * 0.16
      }
    }

    // Poses manuales (Tapar / De reojo)
    const stepTo = (cur, target) => {
      const v = cur + (target - cur) * lerpRate
      return Math.abs(target - v) < 0.001 ? target : v
    }
    this.poseAmount = stepTo(this.poseAmount, +!!this.pose)
    this.peekAmount = stepTo(this.peekAmount, +(this.pose === 'peek'))

    if (this.poseAmount > 0) {
      const fwd = Math.min(1, this.poseAmount / 0.45)
      const up = Math.max(0, (this.poseAmount - 0.45) / 0.55)
      ;[targetL, targetR].forEach((tVec, idx) => {
        const [rx, ry, rz] = HAND_REST_POS[idx]
        const dir = idx === 0 ? -1 : 1
        tVec.set(rx - dir * 0.55 * up, ry + 0.38 * up, rz + 0.82 * fwd)
      })
      targetR.addScaledVector(new THREE.Vector3(0.12, -0.34, -0.06), this.peekAmount)
    }

    // Reacciones de manos ante Poke / Boop y Mareo
    if (pokeEnv > 0) {
      if (this.pokeCount === 1) {
        const wavePoke = Math.sin(elapsed * 18) * 0.14 * pokeEnv
        targetL.set(-0.82 + wavePoke, 0.38 * pokeEnv, 0.36)
        targetR.set(0.82 - wavePoke, 0.38 * pokeEnv, 0.36)
      } else if (this.pokeCount === 2) {
        const tickle = Math.sin(elapsed * 26) * 0.06 * pokeEnv
        targetL.set(-0.42 + tickle, -0.18 + Math.abs(tickle), 0.62)
        targetR.set(0.42 - tickle, -0.18 + Math.abs(tickle), 0.62)
      }
    }
    if (dizzyEnv > 0) {
      const bal = Math.sin(elapsed * 11) * 0.24 * dizzyEnv
      targetL.set(-1.08, -0.05 + bal, 0.22)
      targetR.set(1.08, -0.05 - bal, 0.22)
    }

    // Flotación continua (Idle) y movimientos verticales
    this.idleAmount += ((state === 'paused' || !this.idle ? 0 : 1) - this.idleAmount) * 0.09
    if (this.idleAmount < 0.001) this.idleAmount = 0

    const wave = animate ? Math.sin(elapsed * 2) * this.idleAmount : 0
    const rollbackStepBob =
      state === 'rolling-back' && animate
        ? Math.abs(Math.sin(elapsed * 5.2)) * 0.045 * activeFade
        : 0
    root.position.y =
      BASE_Y +
      wave * 0.04 +
      rollbackStepBob +
      (isRunning && animate ? Math.abs(Math.sin(elapsed * 10)) * 0.035 * loopFade : 0)
    root.rotation.z = animate ? Math.sin(elapsed * 1.6) * 0.02 * this.idleAmount : 0

    const rollbackTilt =
      state === 'rolling-back' && animate ? Math.sin(elapsed * 5.2) * 0.055 * activeFade : 0
    const listenTilt = state === 'listening' && animate ? -0.09 * activeFade : 0
    const noResTilt =
      state === 'no-results' && animate && noResPhase > 0.5
        ? Math.sin(((noResPhase - 0.5) / 0.5) * Math.PI) * 0.08
        : 0
    root.rotation.z +=
      curiosity * 0.1745 +
      readySway * 0.065 +
      dizzyEnv * Math.sin(elapsed * 11) * 0.22 +
      listenTilt +
      noResTilt +
      rollbackTilt
    root.position.x += readySway * 0.045 + dizzyEnv * Math.cos(elapsed * 11) * 0.09
    root.position.y +=
      sleepBreath * 0.065 + stretchEnv * 0.08 + pokeEnv * (this.pokeCount === 0 ? 0.44 : 0.22)

    if (animate && this.idle && state !== 'paused' && this.poseAmount === 0 && this.gestures) {
      const lagY = Math.sin((elapsed - 0.23) * 2) - Math.sin(elapsed * 2)
      const lagZ = Math.sin((elapsed - 0.23) * 1.6) - Math.sin(elapsed * 1.6)
      targetL.y += 0.047 * lagY - 0.022 * lagZ
      targetR.y += 0.047 * lagY + 0.022 * lagZ
    }

    if (state === 'rate-limited' && cooldown) {
      root.position.y =
        BASE_Y + 0.026 * rechargeBreath - cooldownSigh * 0.045 + cooldownReadyPop * 0.18
      root.rotation.z *= 0.25
      root.rotation.z += cooldownGlance * 0.055
      if (animate && this.idle) {
        const t = cooldown.tCycle - 7850
        const prepare = smoothstep(cooldown.tCycle / 7400) * (1 - smoothstep(t / 180))
        const dash = smoothstep(t / 200) * (1 - smoothstep((t - 550) / 400))
        root.position.z = -0.18 * prepare + 0.34 * dash
        root.position.y -= 0.03 * prepare
        root.rotation.x += -0.04 * prepare + 0.11 * dash
      }
    }

    // Anticipación y Seguimiento (Disney Squash & Stretch)
    let squashY =
      1 +
      (cooldown
        ? rechargeBreath * 0.018 - cooldownSigh * 0.04 + cooldownReadyPop * 0.12
        : wave * 0.012)
    if (state === 'deploying') {
      if (stateElapsed < 240) {
        const prep = smoothstep(stateElapsed / 240)
        root.position.y -= 0.18 * prep
        squashY *= 1 - 0.18 * prep
      } else {
        const launch = 1 - Math.pow(1 - clamp01((stateElapsed - 240) / 520), 3)
        const hover = animate ? Math.sin(elapsed * 6) * 0.06 + Math.sin(elapsed * 28) * 0.008 : 0
        root.position.y += 0.58 * launch + hover
        squashY *= 1 + 0.12 * Math.sin(clamp01((stateElapsed - 240) / 450) * Math.PI)
      }
    } else if (state === 'success') {
      if (dt < 160) {
        const a = smoothstep(dt / 160)
        root.position.y -= 0.14 * a
        squashY *= 1 - 0.15 * a
      } else if (dt < 820) {
        const hopT = clamp01((dt - 160) / 660)
        const arc = 4 * hopT * (1 - hopT)
        root.position.y += 0.95 * arc
        squashY *= 1 + 0.16 * Math.sin(hopT * Math.PI)
        targetL.y += 0.55 * arc
        targetL.x -= 0.12 * arc
        targetR.y += 0.55 * arc
        targetR.x += 0.12 * arc
      } else if (dt < 1120) {
        const l = clamp01((dt - 820) / 300)
        const settle = Math.sin(l * Math.PI) * (1 - l)
        root.position.y -= 0.06 * settle
        squashY *= 1 - 0.09 * settle
      }

      accChildren.forEach((c, idx) => {
        const p = clamp01((dt - 160 - (idx % 4) * 35) / 1080)
        const ease = 1 - Math.pow(1 - p, 3)
        c.visible = p > 0 && p < 1
        c.position
          .set(head.position.x, head.position.y + 0.2, head.position.z + 0.26)
          .lerp(c.userData.target, ease)
        c.position.y -= 1.05 * p * p
        if (c instanceof THREE.Mesh) c.material.opacity = Math.min(1, (1 - p) / 0.35)
        c.rotation.z = (idx % 2 ? -1 : 1) * p * (3 + (idx % 5) * 0.5)
      })
    } else if (state === 'error' && stateElapsed < 500) {
      const errT = clamp01(stateElapsed / 500)
      squashY *= 1 - 0.1 * Math.sin(errT * Math.PI * 3) * (1 - errT)
    }

    if (pokeEnv > 0) {
      const boing = Math.sin(pokeT * Math.PI * 3) * (1 - pokeT) * 0.22
      squashY *= 1 + boing
    }
    if (stretchEnv > 0) {
      squashY *= 1 + 0.08 * stretchEnv
    }

    root.scale.set(1 / Math.sqrt(squashY), squashY, 1 / Math.sqrt(squashY))

    // Física de Resorte (Damped Spring Physics) en las Manos
    const stiffness = 210
    const damping = 16
    ;[targetL, targetR].forEach((tVec, idx) => {
      const sp = this.handSprings[idx]
      sp.target.copy(tVec)
      if (!animate) {
        sp.pos.copy(tVec)
        sp.vel.set(0, 0, 0)
      } else {
        const ax = (sp.target.x - sp.pos.x) * stiffness - sp.vel.x * damping
        const ay = (sp.target.y - sp.pos.y) * stiffness - sp.vel.y * damping
        const az = (sp.target.z - sp.pos.z) * stiffness - sp.vel.z * damping
        sp.vel.x += ax * dtSec
        sp.vel.y += ay * dtSec
        sp.vel.z += az * dtSec
        sp.pos.x += sp.vel.x * dtSec
        sp.pos.y += sp.vel.y * dtSec
        sp.pos.z += sp.vel.z * dtSec
      }
      hands[idx].position.copy(sp.pos)
    })

    const grip = this.accessory.getObjectByName('grip')
    if (grip) {
      grip.position.set(
        handR.position.x + 0.1 - reviewActive * 0.18,
        handR.position.y + reviewActive * 0.05,
        handR.position.z + 0.31,
      )
      grip.rotation.z = ((3 * Math.PI) / 4) * reviewActive + reviewScanX * 0.12
    }

    const wrenchGrip = this.accessory.getObjectByName('wrenchGrip')
    if (wrenchGrip && state === 'healing') {
      const crank = Math.sin(elapsed * 7.5)
      const wrenchAngle = 0.44 + (crank > 0 ? crank * 0.36 : crank * 0.1)
      const gripDist = 0.44
      wrenchGrip.position.set(
        handR.position.x - Math.sin(wrenchAngle) * gripDist,
        handR.position.y + Math.cos(wrenchAngle) * gripDist,
        handR.position.z + 0.34,
      )
      const tool = wrenchGrip.getObjectByName('wrenchTool')
      if (tool) tool.rotation.z = wrenchAngle
    }

    const gitMerge = this.accessory.getObjectByName('gitMerge')
    if (gitMerge && state === 'merging') {
      gitMerge.position.set(
        (handL.position.x + handR.position.x) * 0.5,
        (handL.position.y + handR.position.y) * 0.5,
        Math.max(handL.position.z, handR.position.z) + 0.28,
      )
    }

    this.blendPose(now)

    // Resplandor Facial Reactivo (Visor Subsurface Light sobre manos y bisel)
    const eyeHex = isDizzy
      ? '#fde047'
      : state === 'rate-limited' && cooldownReadyPop > 0.2
        ? '#4ade80'
        : (cfg.eyeColor ?? (state === 'error' ? '#ff453a' : this.colors.glow))
    this.visorLight.color.set(eyeHex)
    const visorWorld = visor.getWorldPosition(new THREE.Vector3())
    let minHandDist = 99
    hands.forEach((h) => {
      const hWorld = h.getWorldPosition(new THREE.Vector3())
      const d = hWorld.distanceTo(visorWorld)
      if (d < minHandDist) minHandDist = d
      const prox = clamp01(1 - (d - 0.42) / 0.52)
      h.material.emissive.set(eyeHex)
      h.material.emissiveIntensity = prox * 0.28
    })
    const lightProx = clamp01(1 - (minHandDist - 0.42) / 0.55)
    this.visorLight.intensity = 0.65 + lightProx * 2.15

    if (badgeScale && accChildren[0]) {
      accChildren[0].position.copy(handR.position)
      accChildren[0].position.z += 0.34
      if (cfg.accessory === 'alert') accChildren[0].translateY(-0.055 * accChildren[0].scale.y)
    }

    // Sombra Dinámica Reactiva a la Altura Y y Posición X
    const heightDelta = Math.max(0, root.position.y - BASE_Y)
    const shadowScale = Math.max(0.42, (1 - wave * 0.04) * (1 - heightDelta * 0.44))
    floor.position.x = root.position.x
    floor.position.z = root.position.z
    floor.scale.set(shadowScale, shadowScale, 1)
    floor.material.opacity = Math.max(0.22, 1 - heightDelta * 0.65)

    this.updateScreen(
      now,
      animate,
      gestureEnv,
      thinkEnv,
      activeDagNodePos,
      activeTestIdx,
      juggleEnv,
      isDizzy,
      pokeEnv,
      recallCycle,
      checkCycle,
      noResPhase,
      activeRouterPos,
      benchArmTilt,
      cooldownGlance,
      cooldownReadyPop,
    )
  }

  updateScreen(
    now,
    animate,
    gestureEnv,
    thinkEnv,
    activeDagNodePos,
    activeTestIdx,
    juggleEnv = 0,
    isDizzy = false,
    pokeEnv = 0,
    recallCycle = 0,
    checkCycle = 0,
    noResPhase = 0,
    activeRouterPos = null,
    benchArmTilt = 0,
    cooldownGlance = 0,
    cooldownReadyPop = 0,
  ) {
    const showProg = this.state === 'running' && this.progress !== null
    if (!this.message && this.ticker && now >= this.tickerAt && animate) {
      const t = this.ticker
      this.message = { text: t.lines[t.i], at: now, scroll: true }
      t.i = t.i + 1 < t.lines.length ? t.i + 1 : Math.min(t.loopFrom, t.lines.length - 1)
    }
    if (this.message?.scroll && now >= this.message.at) {
      // Letrero que entra por la derecha y sale por la izquierda (SCROLL_SPEED px/s del visor).
      const x = 512 - ((now - this.message.at) / 1000) * SCROLL_SPEED
      if (x + ledWidth(this.message.text, SCROLL_SCALE) > 0 && animate) {
        const key = `scroll:${this.message.text}:${Math.round(x)}`
        if (key !== this.drawnKey) {
          this.drawnKey = key
          drawLedScreen(this.faceCtx, this.message.text, {
            count: [...this.message.text].length,
            cursor: false,
            error: this.message.error,
            scrollX: x,
          })
          this.faceTexture.needsUpdate = true
        }
        return
      }
      this.message = null
      this.tickerAt = now + (this.ticker?.gap ?? 0)
    }
    if (this.message && now >= this.message.at) {
      if (showProg && this.compact) {
        this.message = null
      } else {
        const chars = [...this.message.text]
        const dt = now - this.message.at
        const instant = !this.gestures || !animate
        const charTime = instant ? 0 : chars.length * 90
        const count = instant
          ? chars.length
          : Math.max(0, Math.min(chars.length, Math.floor(dt / 90)))
        const cursor = !instant && (dt < charTime || Math.floor((dt - charTime) / 240) % 2 === 0)
        if (dt < charTime + (this.message.hold ?? 1000)) {
          this.showingProgress = false
          const key = `led:${this.message.text}:${count}:${cursor}:${!!this.message.error}`
          if (key !== this.drawnKey) {
            this.drawnKey = key
            drawLedScreen(this.faceCtx, this.message.text, {
              count,
              cursor,
              error: this.state === 'error' || this.message.error,
            })
            this.faceTexture.needsUpdate = true
          }
          return
        }
        const next = this.sayQueue?.shift()
        this.message = next ? { ...next, at: now + 120 } : null
        this.tickerAt = Math.max(this.tickerAt ?? 0, now + (this.ticker?.gap ?? 0))
      }
    }

    this.showingProgress = showProg && this.message === null
    if (this.showingProgress) {
      const txt = `${this.progress[0]}/${this.progress[1]}`
      const key = `led:${txt}:${txt.length}:false`
      if (key !== this.drawnKey) {
        this.drawnKey = key
        drawLedScreen(this.faceCtx, txt, { count: txt.length, cursor: false, big: true })
        this.faceTexture.needsUpdate = true
      }
      return
    }

    const cfg = STATE_CONFIG[this.state]
    const isCalm = ['calm', 'ready'].includes(this.state)
    const canTrackPointer = !['sleeping', 'paused', 'cancelled', 'learning', 'listening'].includes(
      this.state,
    )
    const canMove = animate && this.idle && this.gestures

    let ptrX = 0,
      ptrY = 0,
      tracking = false
    if (canTrackPointer && canMove && this.pointerAt) {
      const rect = this.renderer.domElement.getBoundingClientRect()
      const screenWorld = this.model.screen.getWorldPosition(new THREE.Vector3())
      const proj = screenWorld.clone().project(this.camera)
      const screenCenterX = rect.left + ((proj.x + 1) * rect.width) / 2
      const screenCenterY = rect.top + ((1 - proj.y) * rect.height) / 2
      const dx = this.pointerAt.x - screenCenterX
      const dy = this.pointerAt.y - screenCenterY
      const dist = Math.hypot(dx, dy)

      const camRight = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0)
      const triggerWorldRadius = BODY_RADIUS + HAND_SPHERE_RADIUS * 2.1
      const edgeProj = screenWorld
        .clone()
        .addScaledVector(camRight, triggerWorldRadius)
        .project(this.camera)
      const edgeScreenX = rect.left + ((edgeProj.x + 1) * rect.width) / 2
      const edgeScreenY = rect.top + ((1 - edgeProj.y) * rect.height) / 2
      const maxRadiusPx = Math.max(
        90,
        Math.hypot(edgeScreenX - screenCenterX, edgeScreenY - screenCenterY),
      )

      if (proj.z >= -1 && proj.z <= 1 && dist < maxRadiusPx) {
        tracking = true
        const falloff = smoothstep((maxRadiusPx - dist) / (maxRadiusPx * 0.28))
        const normX = Math.max(-1, Math.min(1, dx / (maxRadiusPx * 0.72)))
        const normY = Math.max(-1, Math.min(1, dy / (maxRadiusPx * 0.72)))
        ptrX = normX * 48 * falloff
        ptrY = normY * 34 * falloff
      }
    }
    const dtPtr = this.pointerTime === null ? 16 : Math.max(0, Math.min(50, now - this.pointerTime))
    this.pointerTime = now
    const kPtr = canTrackPointer && canMove ? 1 - Math.exp(-dtPtr / 85) : 1
    this.pointerGaze[0] += (ptrX - this.pointerGaze[0]) * kPtr
    this.pointerGaze[1] += (ptrY - this.pointerGaze[1]) * kPtr
    const usingPointer =
      tracking || Math.abs(this.pointerGaze[0]) + Math.abs(this.pointerGaze[1]) > 0.5

    let autoOff = [0, 0],
      autoBlink = false
    if (usingPointer) {
      autoOff = [this.pointerGaze[0], this.pointerGaze[1]]
    } else if (isCalm && canMove) {
      const mod = (now - this.stateAt) % 6000
      const g =
        mod >= 3900 && mod <= 5400 ? Math.pow(Math.sin((Math.PI * (mod - 3900)) / 1500), 2) : 0
      autoOff =
        this.state === 'calm' && !this.pose ? [Math.round(17 * g), Math.round(-11 * g)] : [0, 0]
      autoBlink = mod >= 4900 && mod < 5040
    } else if (this.state === 'thinking' && canMove && this.gestureAt === null) {
      const mod = Math.max(0, now - this.stateAt - 1450) % 9000
      const g =
        mod >= 1800 && mod < 5000 ? Math.pow(Math.sin((Math.PI * (mod - 1800)) / 3200), 2) : 0
      autoOff = [Math.round(-18 * g) || 0, Math.round(10 * g * Math.sin(mod / 390)) || 0]
    }

    if (
      animate &&
      !isCalm &&
      [
        'pills',
        'happy',
        'wink',
        'circles',
        'nodes',
        'check-eyes',
        'code-eyes',
        'stars',
        'reticle',
        'empty-search',
        'shield-eyes',
      ].includes(cfg.eyes)
    ) {
      if (!this.nextBlink) this.nextBlink = now + 3200 + Math.random() * 3000
      if (now >= this.nextBlink) {
        this.blinkUntil = now + 140
        this.nextBlink = now + 3200 + Math.random() * 3000
      }
    }

    const eyeMode = isDizzy
      ? 'dizzy'
      : pokeEnv > 0
        ? this.pokeCount === 1
          ? 'happy'
          : 'stress'
        : this.state === 'rate-limited' && cooldownReadyPop > 0.25
          ? 'happy'
          : this.state === 'rate-limited' && cooldownGlance > 0.35
            ? 'wink'
            : now < this.blinkUntil || autoBlink
              ? 'bars'
              : this.errorEyes
                ? 'error-pills'
                : cfg.eyes
    const revElapsed = now - this.stateAt
    const revLoopT = Math.max(0, revElapsed - 1500) % 4200
    const revEyeActive =
      this.state === 'reviewing'
        ? Math.max(
            gestureEnv,
            smoothstep(revLoopT / 450) * (1 - smoothstep((revLoopT - 2500) / 550)),
          )
        : 0
    const scanWave =
      this.state === 'rate-limited' && animate
        ? cooldownGlance * 34
        : this.state === 'benchmarking' && animate
          ? -benchArmTilt * 85
          : this.state === 'rolling-back' && animate
            ? Math.sin((now - this.startTime) * 0.0026) * 24
            : this.state === 'delegating'
              ? animate && this.idle
                ? Math.cos((now - this.stateAt) / 1100) * 28
                : 0
              : this.state === 'sandbox'
                ? Math.sin((now - this.startTime) * 0.0022) * 24
                : this.state === 'routing' && activeRouterPos
                  ? activeRouterPos.x * 75
                  : this.state === 'recalling'
                    ? Math.sin(recallCycle * Math.PI) * 28
                    : this.state === 'check-result'
                      ? 16 + Math.sin(checkCycle * Math.PI * 2) * 16
                      : this.state === 'external-wait'
                        ? Math.sin((now - this.startTime) * 0.0019) * 24
                        : this.state === 'no-results' && animate
                          ? noResPhase < 0.5
                            ? Math.sin((noResPhase / 0.5) * Math.PI * 2) * 32
                            : 16
                          : this.state === 'planning' && activeDagNodePos
                            ? activeDagNodePos.x * 38
                            : this.state === 'testing'
                              ? (this.smoothTestIdx - 1) * 32
                              : this.state === 'healing'
                                ? 22
                                : this.state === 'reviewing'
                                  ? revEyeActive * 16 +
                                    Math.sin((now - this.startTime) * 0.0032) * revEyeActive * 14
                                  : this.state === 'auditing'
                                    ? Math.sin((now - this.startTime) * 0.0035) * 22
                                    : this.state === 'offline'
                                      ? Math.sin((now - this.startTime) * 0.0022) * 26
                                      : this.state === 'waiting'
                                        ? Math.sin((now - this.startTime) * 0.003) * 12
                                        : this.state === 'coding'
                                          ? Math.sin((now - this.startTime) * 0.012) * 14
                                          : 0
    const scanY =
      this.state === 'rate-limited' && animate
        ? 12 - cooldownGlance * 24 - cooldownReadyPop * 10
        : this.state === 'benchmarking'
          ? 14
          : this.state === 'rolling-back'
            ? -24
            : this.state === 'sandbox'
              ? Math.sin((now - this.startTime) * 0.0036) * 14
              : this.state === 'routing'
                ? 16
                : this.state === 'recalling'
                  ? -Math.sin(recallCycle * Math.PI) * 22
                  : this.state === 'check-result'
                    ? Math.sin(checkCycle * Math.PI * 2) * 10
                    : this.state === 'no-results' && animate
                      ? noResPhase > 0.5
                        ? 14
                        : -6
                      : juggleEnv > 0
                        ? 22 * juggleEnv
                        : this.state === 'planning' && activeDagNodePos
                          ? -activeDagNodePos.y * 28
                          : this.state === 'testing'
                            ? 18 -
                              Math.sin(
                                (now - this.startTime) * 0.0042 - this.smoothTestIdx * 1.35,
                              ) *
                                7
                            : this.state === 'healing'
                              ? -10
                              : this.state === 'reviewing'
                                ? Math.cos((now - this.startTime) * 0.0048) * revEyeActive * 16
                                : this.state === 'auditing'
                                  ? -Math.sin((now - this.startTime) * 0.0048) * 20
                                  : this.state === 'offline'
                                    ? Math.cos((now - this.startTime) * 0.0044) * 12
                                    : this.state === 'cancelled'
                                      ? 14
                                      : 0
    const scanX = scanWave - thinkEnv * 20
    const ox = Math.max(-62, Math.min(62, Math.round(scanX + autoOff[0] + this.gaze[0] * 52)))
    const oy = Math.max(
      -44,
      Math.min(
        44,
        Math.round(
          (this.state === 'thinking'
            ? -Math.abs(scanX) * 0.6
            : this.state === 'coding'
              ? 10
              : scanY) +
            autoOff[1] +
            this.gaze[1] * 36,
        ),
      ),
    )

    const eyeVel = Math.hypot(ox - this.prevOx, oy - this.prevOy)
    this.prevOx = ox
    this.prevOy = oy
    if (eyeVel > 2.2) {
      this.saccadeMag = Math.min(1, this.saccadeMag + eyeVel * 0.045)
      if (Math.abs(ox - this.prevOx) > 0.5) this.saccadeDir = Math.sign(ox) || 1
    } else {
      this.saccadeMag *= 0.82
      if (this.saccadeMag < 0.02) this.saccadeMag = 0
    }
    const sacMagQ = Math.round(this.saccadeMag * 20) / 20

    let winkMix = 1
    if (this.winkAt !== null) {
      winkMix = Math.round(smoothstep((now - this.winkAt - 160) / 300) * 20) / 20
      if (winkMix >= 1) this.winkAt = null
    }

    const overrideCol = isDizzy
      ? '#fde047'
      : this.state === 'rate-limited' && cooldownReadyPop > 0.25
        ? '#4ade80'
        : cfg.eyeColor
    const animTick =
      eyeMode === 'listening' && animate
        ? Math.floor(now / 65)
        : eyeMode === 'dizzy'
          ? Math.floor(now / 45)
          : 0
    const key = `${eyeMode}:${ox}:${oy}:${overrideCol ?? ''}:${winkMix}:${sacMagQ}:${this.saccadeDir}:${animTick}`
    if (key !== this.drawnKey) {
      this.drawnKey = key
      drawEyesScreen(this.faceCtx, eyeMode, this.colors, [ox, oy], overrideCol, winkMix, [
        eyeMode === 'listening' ? animTick * 0.35 : sacMagQ,
        this.saccadeDir,
      ])
      this.faceTexture.needsUpdate = true
    }
  }
}
