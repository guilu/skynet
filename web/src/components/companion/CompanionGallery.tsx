import { Suspense, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router'
import { Companion } from './Companion'
import { Skyny3D } from './load3d'
import { can3D } from './look'
import { useOutfit } from './outfit'
import { setCompanionPrefs, useCompanionPrefs } from './prefs'
import { SEASONS } from './season'
import { SkynySlot } from './SkynySlot'
import { COMPANION_STATES, type CompanionState } from './state'
import { BACKS, FACES, HATS, type HatKind } from './three/extras'
import type { Pose3D } from './three/scene'
import { STATES_META, type SkynyState } from './three/states'
import { onThemeChange, readColors } from './three/tokens'

const IN_APP = new Set<string>(COMPANION_STATES.map((s) => s.state))
/** Los 12 estados que salen en la web y los 22 de flujos agénticos del estudio. */
const GROUPS = [
  { title: 'Los que ves en la web', states: STATES_META.filter((s) => IN_APP.has(s.state)) },
  { title: 'Flujos agénticos', states: STATES_META.filter((s) => !IN_APP.has(s.state)) },
]
const SEASON_HATS = new Set<HatKind>(SEASONS.map((s) => s.season))

/** Colores del cuerpo de cada estado, al día con el tema. */
function useBodyColors() {
  const [colors, setColors] = useState(() => readColors().body)
  useEffect(() => onThemeChange(() => setColors(readColors().body)), [])
  return colors
}

/**
 * Ajustes › Companion: si se ve la mascota de la barra y si salen avisos (en este navegador), los
 * 45 estados de Skyny en 3D (como en el estudio) y el gorro y los complementos que lleva en toda
 * la web. Sin WebGL o con movimiento reducido, la vista previa es el Skyny 2D.
 */
export function CompanionGallery() {
  const [selected, setSelected] = useState<SkynyState>('calm')
  const info = STATES_META.find((s) => s.state === selected)
  const [pose, setPose] = useState<Pose3D>()
  const [replay, setReplay] = useState(0)
  const [boop, setBoop] = useState(0)
  const [three, setThree] = useState(can3D)
  const outfit = useOutfit()
  const body = useBodyColors()
  const prefs = useCompanionPrefs()
  const [permission, setPermission] = useState(notificationPermission)
  const toggleBrowser = async (on: boolean) => {
    if (on && permission !== 'granted') {
      const answer = await Notification.requestPermission()
      setPermission(answer)
      if (answer !== 'granted') return
    }
    setCompanionPrefs({ browser: on })
  }
  const { hash } = useLocation()
  const section = useRef<HTMLElement>(null)
  // Desde el menú de la mascota se llega con #companion: el router no baja solo hasta aquí.
  useEffect(() => {
    if (hash === '#companion') section.current?.scrollIntoView({ block: 'start' })
  }, [hash])
  return (
    <section
      ref={section}
      id="companion"
      className="card settings-section"
      aria-labelledby="companion-title"
    >
      <h2 id="companion-title">Skyny, tu companion</h2>
      <p className="hint">
        Skyny (de Skynet) es la mascota que te cuenta cómo van tus agentes. Cambia de color, de cara
        y de accesorio según el estado; en reposo toma el color de acento. Toca un estado para
        verlo. Con «reducir movimiento» del sistema se queda quieta y en 2D.
      </p>
      <fieldset className="companion-prefs">
        <legend className="visually-hidden">Preferencias del companion en este navegador</legend>
        <label>
          <input
            type="checkbox"
            checked={prefs.mascot}
            onChange={(e) => setCompanionPrefs({ mascot: e.target.checked })}
          />
          Mostrar la mascota en la barra superior
        </label>
        <label>
          <input
            type="checkbox"
            checked={prefs.toasts}
            onChange={(e) => setCompanionPrefs({ toasts: e.target.checked })}
          />
          Avisos flotantes (la campana los guarda igualmente)
        </label>
        <label>
          <input
            type="checkbox"
            checked={prefs.browser && permission === 'granted'}
            disabled={permission === 'unsupported' || permission === 'denied'}
            onChange={(e) => void toggleBrowser(e.target.checked)}
          />
          Avisos del navegador con la pestaña oculta (te necesita, errores y éxitos)
        </label>
        <label>
          <input
            type="checkbox"
            checked={prefs.seasonal}
            onChange={(e) => setCompanionPrefs({ seasonal: e.target.checked })}
          />
          Accesorios de temporada (Halloween, Navidad y verano)
        </label>
        {permission === 'denied' && (
          <p className="hint">
            El navegador tiene bloqueados los avisos de esta web: permítelos en la configuración del
            sitio para activarlos.
          </p>
        )}
        {permission === 'unsupported' && <p className="hint">Este navegador no admite avisos.</p>}
        <p className="hint">Estas opciones valen solo para este navegador.</p>
      </fieldset>
      <div className="companion-stage">
        <div className="companion-preview">
          {three ? (
            <Suspense fallback={<SkynySlot size={200} />}>
              <Skyny3D
                state={selected}
                size={200}
                pose={pose}
                outfit={outfit}
                replay={replay}
                boop={boop}
                onFail={() => setThree(false)}
              />
            </Suspense>
          ) : (
            <Companion
              state={IN_APP.has(selected) ? (selected as CompanionState) : 'calm'}
              size={144}
              label="Mascota de Skynet"
              pose={pose}
              look
            />
          )}
        </div>
        <div>
          <div aria-live="polite">
            <h3>{info?.label}</h3>
            <p className="muted">{info?.when}</p>
          </div>
          <div className="companion-moves" role="group" aria-label="Gestos de Skyny">
            <button
              type="button"
              className="btn btn-sm btn-secondary"
              onClick={() => setReplay((n) => n + 1)}
            >
              Repetir gesto
            </button>
            <button
              type="button"
              className="btn btn-sm btn-secondary"
              onClick={() => setBoop((n) => n + 1)}
            >
              ¡Boop!
            </button>
            {POSES.map(({ pose: p, label }) => (
              <button
                key={p}
                type="button"
                className="btn btn-sm btn-secondary"
                aria-pressed={pose === p}
                onClick={() => setPose(pose === p ? undefined : p)}
              >
                {label}
              </button>
            ))}
          </div>
          <fieldset className="companion-outfit">
            <legend>Lo que lleva puesto</legend>
            <label>
              Gorro
              <select
                className="input"
                value={prefs.hat}
                onChange={(e) => setCompanionPrefs({ hat: e.target.value })}
              >
                {HATS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.value === '' && prefs.seasonal ? 'El de temporada' : o.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Cara
              <select
                className="input"
                value={prefs.face}
                onChange={(e) => setCompanionPrefs({ face: e.target.value })}
              >
                {FACES.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Espalda
              <select
                className="input"
                value={prefs.back}
                onChange={(e) => setCompanionPrefs({ back: e.target.value })}
              >
                {BACKS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <p className="hint">
              Skyny lo lleva en toda la web: el login, la cortina del chat y En curso.
              {!prefs.hat && SEASON_HATS.has(outfit.hat) && ' Ahora lleva el gorro de temporada.'}
            </p>
          </fieldset>
        </div>
      </div>
      {GROUPS.map(({ title, states }) => (
        <div key={title}>
          <h3 className="companion-group-title">
            {title} <span className="muted num">{states.length}</span>
          </h3>
          <ul className="companion-states">
            {states.map(({ state, label }) => (
              <li key={state}>
                <button
                  type="button"
                  className="companion-state"
                  aria-pressed={state === selected}
                  onClick={() => {
                    setSelected(state)
                    setPose(undefined)
                  }}
                >
                  <span
                    className="companion-swatch"
                    style={{ background: body[state] }}
                    aria-hidden="true"
                  />
                  {label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
      <p className="hint">
        Gorros de temporada:{' '}
        {SEASONS.map(
          ({ label, when }) => `${label}, ${when[0].toLowerCase()}${when.slice(1, -1)}`,
        ).join('; ')}
        .
      </p>
    </section>
  )
}

/** Las poses del login, para verlas en la vista previa. */
const POSES: { pose: Pose3D; label: string }[] = [
  { pose: 'cover', label: 'Taparse los ojos' },
  { pose: 'peek', label: 'De reojo' },
]

function notificationPermission(): NotificationPermission | 'unsupported' {
  return typeof Notification === 'undefined' ? 'unsupported' : Notification.permission
}
