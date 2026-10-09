import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type CSSProperties } from 'react'
import { api, type Appearance } from '../api'
import { APPEARANCE_KEY, useAppearance } from '../appearance'
import { ErrorMessage } from '../components/ErrorMessage'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import { LinesSkeleton } from '../components/ui/Skeleton'
import {
  cleanPalette,
  DEFAULT_BASE,
  derivePalette,
  PRESETS,
  previewStyle,
  samePalette,
  SLOTS,
  type DerivedPalette,
  type Mode,
  type Palette,
} from '../lib/palette'

/** Ajustes de la web que valen para todos los navegadores. De momento, la apariencia. */
export function SettingsPage() {
  const appearance = useAppearance()
  return (
    <>
      <h1>Ajustes</h1>
      <p className="muted">Lo que cambies aquí vale para toda la web y en todos los navegadores.</p>
      <ErrorMessage error={appearance.error} />
      {appearance.isPending && <LinesSkeleton label="la apariencia" />}
      {appearance.data && <AppearanceForm saved={appearance.data.colors} />}
    </>
  )
}

function AppearanceForm({ saved }: { saved: Palette }) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<Palette>(() => cleanPalette(saved))
  const derived = useMemo(() => derivePalette(draft), [draft])
  const dirty = !samePalette(draft, saved)
  const preset = PRESETS.find((p) => samePalette(p.colors, draft))?.id
  const save = useMutation({
    mutationFn: () => {
      const colors: Appearance['colors'] = {}
      for (const { slot } of SLOTS) colors[slot] = draft[slot] ?? null
      return api.saveAppearance(colors)
    },
    onSuccess: (appearance) => queryClient.setQueryData(APPEARANCE_KEY, appearance),
  })
  const change = (next: Palette) => {
    save.reset()
    setDraft(cleanPalette(next))
  }

  return (
    <div className="settings">
      <section className="card settings-section" aria-labelledby="palettes-title">
        <h2 id="palettes-title">Apariencia</h2>
        <p className="hint">
          Elige una paleta o cambia cada color abajo. Las sombras, los fondos y los colores del tema
          oscuro se calculan solos, y el texto siempre llega al contraste AA.
        </p>
        <fieldset className="preset-grid">
          <legend className="visually-hidden">Paleta</legend>
          {PRESETS.map((p) => (
            <label key={p.id} className="preset">
              <input
                type="radio"
                name="preset"
                checked={preset === p.id}
                onChange={() => change(p.colors)}
              />
              <span className="preset-swatches" aria-hidden="true">
                {SLOTS.map(({ slot }) => (
                  <span
                    key={slot}
                    className="swatch"
                    style={{ background: p.colors[slot] ?? DEFAULT_BASE[slot] }}
                  />
                ))}
              </span>
              <span className="preset-name">{p.name}</span>
            </label>
          ))}
        </fieldset>
      </section>

      <div className="settings-columns">
        <section className="card settings-section" aria-labelledby="colors-title">
          <h2 id="colors-title">Colores</h2>
          <ul className="color-list">
            {SLOTS.map(({ slot, label, hint }) => {
              const value = draft[slot] ?? DEFAULT_BASE[slot]
              return (
                <li key={slot} className="color-row">
                  <input
                    id={`color-${slot}`}
                    type="color"
                    className="color-input"
                    value={value}
                    aria-describedby={`color-${slot}-hint`}
                    onChange={(e) => change({ ...draft, [slot]: e.target.value })}
                  />
                  <span className="color-text">
                    <label htmlFor={`color-${slot}`}>{label}</label>
                    <span id={`color-${slot}-hint`} className="hint">
                      {hint}
                    </span>
                  </span>
                  <code className="color-hex">{value}</code>
                  {draft[slot] ? (
                    <Button
                      variant="link"
                      aria-label={`Volver al color de Skynet en ${label}`}
                      onClick={() => change({ ...draft, [slot]: null })}
                    >
                      Por defecto
                    </Button>
                  ) : (
                    <span className="muted small">Skynet</span>
                  )}
                </li>
              )
            })}
          </ul>
        </section>

        <section className="card settings-section" aria-labelledby="preview-title">
          <h2 id="preview-title">Vista previa</h2>
          <div className="palette-previews">
            <Preview derived={derived} mode="light" />
            <Preview derived={derived} mode="dark" />
          </div>
        </section>
      </div>

      {derived.issues.length > 0 && (
        <div className="action-panel" role="alert">
          <p>
            <strong>Esta paleta no se puede guardar:</strong>
          </p>
          <ul>
            {derived.issues.map((issue) => (
              <li key={`${issue.slot}-${issue.mode}`}>
                {SLOTS.find((s) => s.slot === issue.slot)?.label}, tema{' '}
                {issue.mode === 'light' ? 'claro' : 'oscuro'}: {issue.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="form-actions settings-actions">
        <Button
          onClick={() => save.mutate()}
          disabled={!dirty || derived.issues.length > 0 || save.isPending}
        >
          {save.isPending ? 'Guardando…' : 'Guardar'}
        </Button>
        <Button
          variant="secondary"
          onClick={() => change({})}
          disabled={Object.keys(draft).length === 0}
        >
          Volver a los colores de Skynet
        </Button>
        {dirty && !save.isPending && <span className="hint">Cambios sin guardar.</span>}
        {save.isSuccess && !dirty && (
          <span className="saved" role="status">
            Guardado.
          </span>
        )}
      </div>
      <ErrorMessage error={save.error} />
    </div>
  )
}

/** Las piezas de la web con la paleta en borrador, en un tema. No se pueden usar. */
function Preview({ derived, mode }: { derived: DerivedPalette; mode: Mode }) {
  const title = mode === 'light' ? 'Claro' : 'Oscuro'
  return (
    <figure
      className="palette-preview"
      aria-label={`Vista previa en tema ${title.toLowerCase()}`}
      style={previewStyle(derived, mode) as CSSProperties}
    >
      <figcaption>{title}</figcaption>
      <div className="preview-row">
        <span className="btn btn-sm">Lanzar agente</span>
        <span className="btn btn-sm btn-success">Aprobar</span>
        <span className="btn btn-sm btn-danger">Eliminar</span>
      </div>
      <div className="preview-row">
        <Pill tone="ok">Correcta</Pill>
        <Pill tone="warn">Sin latido</Pill>
        <Pill tone="bad">Fallida</Pill>
        <Pill tone="active">En curso</Pill>
        <Pill tone="neutral">En espera</Pill>
      </div>
      <p className="preview-text">
        Texto con un <span className="preview-link">enlace</span> y una{' '}
        <span className="preview-mark">selección</span>.
      </p>
      <div className="preview-bars" aria-hidden="true">
        <span style={{ background: 'var(--ok)', flexGrow: 4 }} />
        <span style={{ background: 'var(--live)', flexGrow: 3 }} />
        <span style={{ background: 'var(--warn)', flexGrow: 2 }} />
        <span style={{ background: 'var(--bad)', flexGrow: 1 }} />
        <span style={{ background: 'var(--idle)', flexGrow: 2 }} />
      </div>
      <pre className="preview-diff">
        <span className="diff-del">- return a - b</span>
        <span className="diff-add">+ return a + b</span>
      </pre>
    </figure>
  )
}
