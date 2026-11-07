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

document.body.innerHTML = `
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
        <ul></ul>
      </aside>
    </main>
  </div>`

const $ = <T extends Element>(selector: string) => document.querySelector<T>(selector)!
const canvas = $<HTMLCanvasElement>('.st-canvas')
const stage = $<HTMLDivElement>('.st-stage')
const list = $<HTMLUListElement>('.st-states ul')

const skyny = new SkynyScene(canvas, readColors(), { gestures: !reducedMotion })
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

const fit = () => {
  const { width, height } = stage.getBoundingClientRect()
  skyny.resize(Math.max(1, width), Math.max(1, height))
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
  const { state, view: v, pose: p, action } = button.dataset
  if (state) setState(state as CompanionState)
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
skyny.play(() => controls.update())

// Para depurar desde la consola del navegador.
Object.assign(window, { skyny, controls })
