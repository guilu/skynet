import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../../api'
import { APPEARANCE_KEY, applyIconPack } from '../../appearance'
import { cn } from '../../lib/cn'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { Button } from '../ui/Button'
import { Icon } from './Icon'
import { CHATGPT } from './chatgpt'
import { ILUSTRADOS } from './ilustrados'
import { ICON_PACKS, IconPackContext, type IconPack } from './pack'
import { GROUPS, ICON_NAMES, ICONS, type IconName } from './registry'
import { hasOwnDrawing } from './variant'

const PREVIEW_NAV: IconName[] = ['dashboard', 'projects', 'workflows', 'runs', 'approvals']
const PREVIEW_STATUSES = ['THINKING', 'WAITING_FOR_APPROVAL', 'SUCCEEDED', 'FAILED']

/**
 * Ajustes › Iconos (IC-A): el paquete de iconos de toda la web, con una vista previa de cada uno,
 * y la galería con todos los iconos en sus tamaños y, en Ilustrados, su gesto.
 */
export function IconSettings({ saved }: { saved: IconPack }) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<IconPack>(saved)
  const save = useMutation({
    mutationFn: () => api.saveIconPack(draft),
    onSuccess: (appearance) => {
      queryClient.setQueryData(APPEARANCE_KEY, appearance)
      applyIconPack(appearance.icons ?? draft)
    },
  })
  const dirty = draft !== saved
  return (
    <div className="settings">
      <section className="card settings-section" aria-labelledby="packs-title">
        <h2 id="packs-title">Paquete de iconos</h2>
        <p className="hint">
          Vale para toda la web y en todos los navegadores. Los ilustrados siguen a los colores y al
          tema; los de ChatGPT llevan sus propios colores. Ambos hacen su gesto al pasar el ratón y,
          con «reducir movimiento» del sistema, se quedan quietos.
        </p>
        <fieldset className="icon-packs">
          <legend className="visually-hidden">Paquete de iconos</legend>
          {ICON_PACKS.map((p) => (
            <label key={p.id} className="preset icon-pack">
              <input
                type="radio"
                name="icon-pack"
                value={p.id}
                checked={draft === p.id}
                onChange={() => {
                  save.reset()
                  setDraft(p.id)
                }}
              />
              <span className="icon-pack-head">
                <span className="preset-name">{p.label}</span>
                {saved === p.id && <span className="muted small">En uso</span>}
              </span>
              <span className="hint">{p.hint}</span>
              <PackPreview pack={p.id} />
            </label>
          ))}
        </fieldset>
        <div className="form-actions settings-actions">
          <Button onClick={() => save.mutate()} disabled={!dirty || save.isPending}>
            {save.isPending ? 'Guardando…' : 'Guardar'}
          </Button>
          {dirty && !save.isPending && <span className="hint">Cambios sin guardar.</span>}
          {save.isSuccess && !dirty && (
            <span className="saved" role="status">
              Guardado.
            </span>
          )}
        </div>
        <ErrorMessage error={save.error} />
      </section>
      <IconGallery initial={draft} />
    </div>
  )
}

/** Unas piezas de la web pintadas con un paquete: navegación, botones y píldoras. */
function PackPreview({ pack }: { pack: IconPack }) {
  return (
    <IconPackContext.Provider value={pack}>
      <span className="icon-pack-preview" aria-hidden="true">
        <span className="icon-pack-nav">
          {PREVIEW_NAV.map((name, i) => (
            <span key={name} className={cn('icon-pack-link ico-host', i === 0 && 'active')}>
              <Icon name={name} size={24} strokeWidth={2.25} />
              {ICONS[name].label}
            </span>
          ))}
        </span>
        <span className="icon-pack-side">
          <span className="preview-row">
            <span className="btn btn-sm ico-host">
              <Icon name="play" size={18} strokeWidth={2.5} />
              Lanzar
            </span>
            <span className="btn btn-sm btn-secondary ico-host">
              <Icon name="save" size={18} strokeWidth={2.5} />
              Guardar
            </span>
          </span>
          <span className="preview-row">
            {PREVIEW_STATUSES.map((s) => (
              <StatusBadge key={s} status={s} />
            ))}
          </span>
        </span>
      </span>
    </IconPackContext.Provider>
  )
}

/** Todos los iconos, por grupos, en sus tres tamaños. */
function IconGallery({ initial }: { initial: IconPack }) {
  const [pack, setPack] = useState<IconPack>(initial)
  const [play, setPlay] = useState(false)
  const [still, setStill] = useState(false)
  const own = ICON_NAMES.filter((n) => hasOwnDrawing(pack, n)).length
  return (
    <section className="card settings-section" aria-labelledby="gallery-title">
      <h2 id="gallery-title">Galería</h2>
      <p className="hint">
        Pasa el ratón por un icono para ver su gesto.
        {pack !== 'lucide' &&
          (own === ICON_NAMES.length
            ? ' Todos tienen su dibujo.'
            : ` ${own} de ${ICON_NAMES.length} tienen ya su dibujo; el resto se pinta con Lucide hasta que lo tengan.`)}
      </p>
      <div className="icon-gallery-controls">
        <div className="segmented" role="group" aria-label="Paquete de la galería">
          {ICON_PACKS.map((p) => (
            <button
              key={p.id}
              type="button"
              aria-pressed={pack === p.id}
              onClick={() => setPack(p.id)}
            >
              {p.label}
            </button>
          ))}
        </div>
        <label>
          <input type="checkbox" checked={play} onChange={(e) => setPlay(e.target.checked)} />
          Animar todo
        </label>
        <label>
          <input type="checkbox" checked={still} onChange={(e) => setStill(e.target.checked)} />
          Movimiento reducido
        </label>
      </div>
      <IconPackContext.Provider value={pack}>
        <div className={cn('icon-gallery', play && !still && 'ico-play', still && 'ico-still')}>
          {GROUPS.map((g) => (
            <section key={g.id} aria-labelledby={`icons-${g.id}`}>
              <h3 id={`icons-${g.id}`}>{g.label}</h3>
              <ul className="icon-grid">
                {ICON_NAMES.filter((n) => ICONS[n].group === g.id).map((name) => (
                  <IconCell key={name} name={name} pack={pack} />
                ))}
              </ul>
            </section>
          ))}
        </div>
      </IconPackContext.Provider>
    </section>
  )
}

function IconCell({ name, pack }: { name: IconName; pack: IconPack }) {
  const drawing = { lucide: undefined, ilustrados: ILUSTRADOS[name], chatgpt: CHATGPT[name] }[pack]
  const fallback = !hasOwnDrawing(pack, name)
  return (
    <li className={cn('icon-cell ico-host', fallback && 'icon-cell-fallback')}>
      <span className="icon-cell-big">
        <Icon name={name} size={48} strokeWidth={1.75} />
      </span>
      <span className="icon-cell-sizes">
        <Icon name={name} size={24} />
        <Icon name={name} size={16} />
      </span>
      <strong>{ICONS[name].label}</strong>
      <span className="icon-cell-note">
        {drawing ? drawing.gesture : fallback ? 'Sin dibujo: usa Lucide' : <code>{name}</code>}
      </span>
    </li>
  )
}
