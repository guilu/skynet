import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router'
import { Companion } from './Companion'
import { setCompanionPrefs, useCompanionPrefs } from './prefs'
import { COMPANION_STATES, type CompanionState } from './state'

/**
 * Ajustes › Companion: si se ve la mascota de la barra y si salen avisos (en este navegador), y
 * todas las expresiones; al elegir una, la grande cambia con su parpadeo.
 */
export function CompanionGallery() {
  const [selected, setSelected] = useState<CompanionState>('ready')
  const info = COMPANION_STATES.find((s) => s.state === selected)
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
        Skyny (de Skynet) es la mascota que te cuenta cómo van tus agentes. Toca una expresión para
        ver el cambio. Sigue al color de acento y al tema; con «reducir movimiento» del sistema se
        queda quieta.
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
        <Companion state={selected} size={144} label="Mascota de Skynet" />
        <div aria-live="polite">
          <h3>{info?.label}</h3>
          <p className="muted">{info?.when}</p>
        </div>
      </div>
      <ul className="companion-gallery">
        {COMPANION_STATES.map(({ state, label }) => (
          <li key={state}>
            <button
              type="button"
              className="companion-tile"
              aria-pressed={state === selected}
              onClick={() => setSelected(state)}
            >
              <Companion state={state} size={72} still />
              {label}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function notificationPermission(): NotificationPermission | 'unsupported' {
  return typeof Notification === 'undefined' ? 'unsupported' : Notification.permission
}
