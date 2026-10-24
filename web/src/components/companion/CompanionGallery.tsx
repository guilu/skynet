import { useState } from 'react'
import { Companion } from './Companion'
import { COMPANION_STATES, type CompanionState } from './state'

/** Ajustes › Companion: todas las expresiones; al elegir una, la grande cambia con su parpadeo. */
export function CompanionGallery() {
  const [selected, setSelected] = useState<CompanionState>('ready')
  const info = COMPANION_STATES.find((s) => s.state === selected)
  return (
    <section className="card settings-section" aria-labelledby="companion-title">
      <h2 id="companion-title">Companion</h2>
      <p className="hint">
        La mascota de Skynet cuenta cómo van tus agentes. Toca una expresión para ver el cambio.
        Sigue al color de acento y al tema; con «reducir movimiento» del sistema se queda quieta.
      </p>
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
