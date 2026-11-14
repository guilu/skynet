// Estudio de Skyny 3D (TD-B): la página `skyny-studio.html` del estudio original, ahora dentro del
// repo y sobre el mismo módulo que la app. Solo para desarrollo (`npm run dev`), no va al build.
// Las vistas, la órbita y los botones son del estudio; el modelo, los estados y las animaciones
// son los de `components/companion/three/`, así que lo que se ve aquí es lo que sale en la web.
import '@fontsource/barlow/latin-400.css'
import '@fontsource/barlow/latin-600.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { applyCachedPalette } from '../appearance'
import { COMPANION_STATES, type CompanionState } from '../components/companion/state'
import { SkynyScene, type Pose3D } from '../components/companion/three/scene'
import { EXPRESSIONS_3D } from '../components/companion/three/states'
import { DEMOS, DemoRunner, type DemoKind } from '../components/companion/three/transitions'
import { onThemeChange, readColors } from '../components/companion/three/tokens'
import '../index.css'
import './studio.css'

applyCachedPalette()

/** Las 6 vistas del turnaround (posición de la cámara y punto al que mira). */
type Vec = [number, number, number]
type ViewKey = 'frontal' | 'threeQuarter' | 'lateral' | 'trasera' | 'threeQuarterRear' | 'superior'
/** Las del estudio original, un poco más lejos para que quepan la cabeza y los accesorios. */
const DISTANCE = 1.45
const view = (title: string, [x, y, z]: Vec): { title: string; pos: Vec; target: Vec } => ({
  title,
  pos: [x * DISTANCE, y * DISTANCE, z * DISTANCE],
  target: [0, 0.1, 0],
})
const VIEWS: Record<ViewKey, { title: string; pos: Vec; target: Vec }> = {
  frontal: view('Frontal', [0, 0.05, 3.8]),
  threeQuarter: view('3/4', [2.5, 0.85, 2.7]),
  lateral: view('Lateral', [3.8, 0.05, 0]),
  trasera: view('Trasera', [0, 0.15, -3.8]),
  threeQuarterRear: view('3/4 trasera', [-2.5, 0.85, -2.7]),
  superior: view('Superior', [0, 4, 0.001]),
}
type View = keyof typeof VIEWS

const POSES: { pose: Pose3D; label: string }[] = [
  { pose: undefined, label: 'Sin pose' },
  { pose: 'cover', label: 'Tapar' },
  { pose: 'peek', label: 'De reojo' },
]

const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
const params = new URLSearchParams(location.search)
const initial = (params.get('state') as CompanionState | null) ?? 'calm'

// Se añade al final del body sin vaciarlo: si el HTML exportado acaba metido dentro del body de
// otra página (p. ej. al publicarlo como vista previa), el <style> del estudio queda ahí y no debe borrarse.
document.body.insertAdjacentHTML(
  'beforeend',
  `
  <div class="st">
    <header class="st-bar">
      <div class="st-title">
        <span class="st-kicker">Skyny 3D</span>
        <h1>Estudio</h1>
      </div>
      <nav class="st-group" aria-label="Vistas">
        ${Object.entries(VIEWS)
          .map(([key, v]) => `<button type="button" data-view="${key}">${v.title}</button>`)
          .join('')}
      </nav>
      <div class="st-group" aria-label="Pose">
        ${POSES.map((p, i) => `<button type="button" data-pose="${i}">${p.label}</button>`).join('')}
      </div>
      <div class="st-group">
        <button type="button" data-action="idle" aria-pressed="true">Idle</button>
        <button type="button" data-action="look" aria-pressed="false">Mirada</button>
        <button type="button" data-action="wire" aria-pressed="false">Malla</button>
        <button type="button" data-action="blink">Parpadear</button>
        <button type="button" data-action="replay">Repetir gesto</button>
        <button type="button" data-action="theme">Tema</button>
        <button type="button" data-action="png">Captura PNG</button>
      </div>
    </header>
    <main class="st-main">
      <div class="st-stage">
        <canvas class="st-canvas" aria-label="Skyny en 3D"></canvas>
        <p class="st-badge"><b data-out="view">Frontal</b> · <span data-out="state"></span></p>
        <p class="st-hint">Orbitar: arrastrar · Zoom: rueda · Paneo: botón derecho</p>
      </div>
      <aside class="st-states">
        <h2>Estados <span data-out="count"></span></h2>
        <div class="st-demos">
          <button type="button" data-action="compact" aria-pressed="false">Vista compacta</button>
          ${Object.entries(DEMOS)
            .map(([kind, d]) => `<button type="button" data-demo="${kind}">${d.label}</button>`)
            .join('')}
          <button type="button" data-action="cancel" disabled>Cancelar tarea</button>
          <button type="button" data-action="retry" hidden>Reintentar</button>
        </div>
        <ul></ul>
      </aside>
    </main>
  </div>`,
)

const $ = <T extends Element>(selector: string) => document.querySelector<T>(selector)!
const canvas = $<HTMLCanvasElement>('.st-canvas')
const stage = $<HTMLDivElement>('.st-stage')
const list = $<HTMLUListElement>('.st-states ul')

const skyny = new SkynyScene(canvas, readColors(), { gestures: !reducedMotion })
const demo = new DemoRunner(skyny)
let compact = false
skyny.camera.fov = 36
skyny.camera.position.set(...VIEWS.frontal.pos)
const controls = new OrbitControls(skyny.camera, canvas)
controls.enableDamping = true
controls.dampingFactor = 0.06
controls.minDistance = 1.8
controls.maxDistance = 10
controls.target.set(0, 0.1, 0)

let looking = false
let current: View = 'frontal'
let pose = 0

/** Vista compacta: Skyny a 180 px en la esquina, con letreros cortos (como iría en la web). */
const COMPACT_PX = 180
const fit = () => {
  const { width, height } = stage.getBoundingClientRect()
  const w = compact ? Math.min(COMPACT_PX, width) : width
  const h = compact ? Math.min(COMPACT_PX, height) : height
  canvas.classList.toggle('st-canvas-compact', compact)
  canvas.style.width = `${Math.max(1, w)}px`
  canvas.style.height = `${Math.max(1, h)}px`
  skyny.resize(Math.max(1, w), Math.max(1, h))
}
new ResizeObserver(fit).observe(stage)
fit()

const colors = () => readColors().body
function renderStates() {
  const body = colors()
  list.innerHTML = COMPANION_STATES.map(({ state, label, when }) => {
    const e = EXPRESSIONS_3D[state]
    return `<li><button type="button" data-state="${state}" aria-pressed="${state === skyny.current}">
      <span class="st-swatch" style="background:${body[state]}"></span>
      <span><b>${label}</b><small>${when}</small><code>${state} · ${e.eyes}${e.accessory ? ` · ${e.accessory}` : ''}</code></span>
    </button></li>`
  }).join('')
  const i = COMPANION_STATES.findIndex((s) => s.state === skyny.current)
  $('[data-out="count"]').textContent = `${i + 1} / ${COMPANION_STATES.length}`
  $('[data-out="state"]').textContent = COMPANION_STATES[i].label
}

function setState(state: CompanionState) {
  skyny.setState(state)
  const url = new URL(location.href)
  url.searchParams.set('state', state)
  history.replaceState(null, '', url)
  renderStates()
}

function setView(key: View) {
  current = key
  const v = VIEWS[key]
  const fromPos = skyny.camera.position.clone()
  const fromTarget = controls.target.clone()
  const start = performance.now()
  const step = (now: number) => {
    const u = Math.min(1, (now - start) / 650)
    const ease = 0.5 - Math.cos(u * Math.PI) / 2
    skyny.camera.position.lerpVectors(fromPos, fromPos.clone().set(...v.pos), ease)
    controls.target.lerpVectors(fromTarget, fromTarget.clone().set(...v.target), ease)
    if (u < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
  $('[data-out="view"]').textContent = v.title
  document.querySelectorAll('[data-view]').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.getAttribute('data-view') === current))
  })
}

function setPose(i: number) {
  pose = i
  skyny.setPose(POSES[i].pose)
  document.querySelectorAll('[data-pose]').forEach((b) => {
    b.setAttribute('aria-pressed', String(Number(b.getAttribute('data-pose')) === pose))
  })
}

document.addEventListener('click', (e) => {
  const button = (e.target as Element).closest('button')
  if (!button) return
  const { state, view: v, pose: p, action, demo: kind } = button.dataset
  if (state) {
    demo.stop()
    setState(state as CompanionState)
  }
  if (kind) demo.toggle(kind as DemoKind)
  if (v) setView(v as View)
  if (p !== undefined) setPose(Number(p))
  const toggle = () => {
    const on = button.getAttribute('aria-pressed') !== 'true'
    button.setAttribute('aria-pressed', String(on))
    return on
  }
  switch (action) {
    case 'idle':
      skyny.setIdle(toggle())
      break
    case 'look':
      looking = toggle()
      if (!looking) skyny.lookAt(0, 0)
      break
    case 'wire':
      skyny.setWireframe(toggle())
      break
    case 'blink':
      skyny.blink()
      break
    case 'replay':
      skyny.setState(skyny.current)
      break
    case 'compact':
      compact = toggle()
      skyny.setCompact(compact)
      fit()
      // En compacto, el encuadre de la web (el del login); al salir, la vista que había.
      if (compact) {
        skyny.camera.position.set(0, 0.12, 4.3)
        controls.target.set(0, 0.1, 0)
      } else setView(current)
      break
    case 'cancel':
      demo.cancel()
      break
    case 'retry':
      demo.retry()
      break
    case 'theme': {
      const root = document.documentElement
      const dark =
        (root.getAttribute('data-theme') ?? (readColors().dark ? 'dark' : 'light')) === 'dark'
      root.setAttribute('data-theme', dark ? 'light' : 'dark')
      break
    }
    case 'png': {
      const a = document.createElement('a')
      a.href = skyny.snapshot()
      a.download = `skyny-3d-${skyny.current}-${current}.png`
      a.click()
      break
    }
  }
})

// En reposo, Skyny sigue con los ojos al ratón cuando pasa cerca de la cara.
canvas.addEventListener('pointermove', (e) => {
  skyny.pointer(
    e.pointerType === 'mouse' && e.buttons === 0 ? { x: e.clientX, y: e.clientY } : null,
  )
})
for (const type of ['pointerleave', 'pointercancel', 'pointerdown'])
  canvas.addEventListener(type, () => skyny.pointer(null))

canvas.addEventListener('pointermove', (e) => {
  if (!looking) return
  const box = canvas.getBoundingClientRect()
  skyny.lookAt(
    (e.clientX - box.left - box.width / 2) / (box.width / 2),
    (e.clientY - box.top - box.height / 2) / (box.height / 2),
  )
})

onThemeChange(() => {
  skyny.setColors(readColors())
  renderStates()
})

setView('frontal')
setPose(0)
setState(COMPANION_STATES.some((s) => s.state === initial) ? initial : 'calm')
// Los botones de la demo, al día con lo que hace Skyny; y la lista, con el estado al que llega.
const cancelButton = $<HTMLButtonElement>('[data-action="cancel"]')
const retryButton = $<HTMLButtonElement>('[data-action="retry"]')
let shown: CompanionState = skyny.current
skyny.play(() => {
  controls.update()
  demo.tick(performance.now())
  cancelButton.disabled = !demo.canCancel
  retryButton.hidden = !demo.waitingRetry
  document.querySelectorAll<HTMLButtonElement>('[data-demo]').forEach((b) => {
    const kind = b.dataset.demo as DemoKind
    b.textContent = demo.kind === kind ? 'Detener demo' : DEMOS[kind].label
    b.setAttribute('aria-pressed', String(demo.kind === kind))
  })
  if (skyny.current !== shown) {
    shown = skyny.current
    renderStates()
  }
})

// Para depurar desde la consola del navegador.
Object.assign(window, { skyny, controls, demo })
