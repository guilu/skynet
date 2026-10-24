import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, Pencil, Pin, PinOff, ShieldAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import {
  api,
  type MemoryContent,
  type MemoryKind,
  type MemoryObservation,
  type MemoryRevision,
} from '../../api'
import { formatDateTime } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { LongText } from '../run/LongText'
import { Button } from '../ui/Button'
import { LinesSkeleton } from '../ui/Skeleton'
import { EDITABLE_KINDS, kindLabel, ORIGIN_LABEL } from './memoryText'

/**
 * Una entrada de la memoria: su contenido, de dónde sale (la ejecución y la invocación), sus
 * versiones y las acciones de revisión. Lo que escriben los agentes se pinta siempre como texto.
 */
export function MemoryDetail({
  id,
  projectId,
  scope,
  editable,
}: {
  id: string
  projectId: string
  /** Nombre del ámbito: el repositorio o «Todo el proyecto». */
  scope: (repositoryId: string | null) => string
  /** El proyecto no está archivado. */
  editable: boolean
}) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const entry = useQuery({ queryKey: ['memory-entry', id], queryFn: () => api.memoryEntry(id) })
  const history = useQuery({
    queryKey: ['memory-history', id],
    queryFn: () => api.memoryHistory(id),
  })
  const refresh = async (o: MemoryObservation) => {
    queryClient.setQueryData(['memory-entry', id], o)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['memory', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['memory-history', id] }),
    ])
  }
  const action = useMutation({
    mutationFn: (a: 'archive' | 'restore' | 'pin' | 'unpin') => api.memoryAction(id, a),
    onSuccess: refresh,
  })

  if (entry.error) return <ErrorMessage error={entry.error} />
  if (!entry.data) return <LinesSkeleton label="la entrada" />
  const o = entry.data
  const archived = o.archivedAt != null
  const busy = action.isPending

  if (editing) {
    return (
      <EditMemoryForm
        observation={o}
        onCancel={() => setEditing(false)}
        onSaved={async (saved) => {
          await refresh(saved)
          setEditing(false)
        }}
      />
    )
  }

  return (
    <article className="memory-detail" aria-labelledby="memory-title">
      <p className="memory-tags">
        <span className={o.kind === 'RUN_LOG' ? 'tag tag-on' : 'tag'}>{kindLabel(o.kind)}</span>
        <span className="tag">{scope(o.repositoryId)}</span>
        <span className="tag">v{o.version}</span>
        {o.pinned && <span className="tag tag-on">fijada</span>}
        {archived && <span className="tag">archivada</span>}
      </p>
      <h2 id="memory-title">{o.title}</h2>
      {o.topicKey && (
        <p className="small muted">
          Tema <code>{o.topicKey}</code>
        </p>
      )}
      {o.redacted && (
        <p className="memory-redacted small" role="note">
          <ShieldAlert size={16} strokeWidth={2.5} aria-hidden="true" />
          Se enmascaró un secreto al guardarla: el texto ya no lo contiene.
        </p>
      )}

      {editable && (
        <div className="memory-actions">
          {!archived && (
            <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>
              <Pencil size={16} strokeWidth={2.5} aria-hidden="true" />
              Editar
            </Button>
          )}
          {!archived && (
            <Button
              size="sm"
              variant="secondary"
              disabled={busy}
              onClick={() => action.mutate(o.pinned ? 'unpin' : 'pin')}
            >
              {o.pinned ? (
                <PinOff size={16} strokeWidth={2.5} aria-hidden="true" />
              ) : (
                <Pin size={16} strokeWidth={2.5} aria-hidden="true" />
              )}
              {o.pinned ? 'Desfijar' : 'Fijar'}
            </Button>
          )}
          <Button
            size="sm"
            variant="secondary"
            disabled={busy}
            onClick={() => action.mutate(archived ? 'restore' : 'archive')}
          >
            {archived ? (
              <ArchiveRestore size={16} strokeWidth={2.5} aria-hidden="true" />
            ) : (
              <Archive size={16} strokeWidth={2.5} aria-hidden="true" />
            )}
            {archived ? 'Restaurar' : 'Archivar'}
          </Button>
        </div>
      )}
      <ErrorMessage error={action.error} />
      {archived && (
        <p className="small muted">
          Archivada el {formatDateTime(o.archivedAt)}: no sale en las búsquedas ni llega a los
          agentes.
        </p>
      )}

      <Fields content={o} />

      <h3>Procedencia</h3>
      <dl className="repo-facts small">
        <div>
          <dt>Versión actual</dt>
          <dd>
            de {ORIGIN_LABEL[o.origin]}
            {o.updatedBy && ` (${o.updatedBy})`}, {formatDateTime(o.updatedAt)}
          </dd>
        </div>
        <div>
          <dt>Creada</dt>
          <dd>{formatDateTime(o.createdAt)}</dd>
        </div>
        {o.sourceWorkflowRunId && (
          <div>
            <dt>Ejecución</dt>
            <dd>
              <Link to={`/runs/${o.sourceWorkflowRunId}`}>Ver la ejecución</Link>
            </dd>
          </div>
        )}
        {o.sourceAgentRunId && (
          <div>
            <dt>Invocación</dt>
            <dd>
              <Link to={`/agent-runs/${o.sourceAgentRunId}`}>Ver la invocación</Link>
            </dd>
          </div>
        )}
      </dl>

      <h3>Versiones</h3>
      <ErrorMessage error={history.error} />
      {history.data && <Revisions revisions={history.data} />}
    </article>
  )
}

function Fields({
  content: c,
}: {
  content: Pick<MemoryContent, 'what' | 'why' | 'where' | 'learned' | 'tags'>
}) {
  const fields: [string, string | null][] = [
    ['Qué', c.what],
    ['Por qué', c.why],
    ['Dónde', c.where],
    ['Qué se aprendió', c.learned],
  ]
  return (
    <>
      {fields
        .filter(([, text]) => text)
        .map(([label, text]) => (
          <section key={label} aria-label={label}>
            <h3>{label}</h3>
            <LongText text={text!} />
          </section>
        ))}
      {c.tags.length > 0 && (
        <p className="tool-chips">
          {c.tags.map((t) => (
            <code key={t} className="tool-chip">
              {t}
            </code>
          ))}
        </p>
      )}
    </>
  )
}

function Revisions({ revisions }: { revisions: MemoryRevision[] }) {
  return (
    <ol className="memory-revisions">
      {revisions.map((r) => (
        <li key={r.version}>
          <details>
            <summary>
              <strong>v{r.version}</strong> · {r.title} ·{' '}
              <span className="muted">
                de {ORIGIN_LABEL[r.origin]}
                {r.changedBy && ` (${r.changedBy})`}, {formatDateTime(r.changedAt)}
              </span>
            </summary>
            <p className="small muted">
              {kindLabel(r.kind)}
              {r.topicKey && (
                <>
                  {' '}
                  · tema <code>{r.topicKey}</code>
                </>
              )}
              {r.sourceAgentRunId && (
                <>
                  {' '}
                  · <Link to={`/agent-runs/${r.sourceAgentRunId}`}>invocación</Link>
                </>
              )}
            </p>
            <Fields content={r} />
          </details>
        </li>
      ))}
    </ol>
  )
}

/** Corrige una entrada: guardarla crea una versión nueva y conserva su procedencia. */
function EditMemoryForm({
  observation: o,
  onCancel,
  onSaved,
}: {
  observation: MemoryObservation
  onCancel: () => void
  onSaved: (saved: MemoryObservation) => Promise<void>
}) {
  const [form, setForm] = useState({
    kind: o.kind,
    title: o.title,
    topicKey: o.topicKey ?? '',
    what: o.what,
    why: o.why ?? '',
    where: o.where ?? '',
    learned: o.learned ?? '',
    tags: o.tags.join(', '),
  })
  const kinds =
    o.kind === 'RUN_LOG'
      ? [{ value: 'RUN_LOG' as const, label: kindLabel('RUN_LOG') }]
      : EDITABLE_KINDS
  const set = (key: keyof typeof form) => (value: string) => setForm({ ...form, [key]: value })
  const save = useMutation({
    mutationFn: () =>
      api.editMemory(o.id, {
        kind: form.kind,
        title: form.title,
        topicKey: form.topicKey.trim() || null,
        what: form.what,
        why: form.why.trim() || null,
        where: form.where.trim() || null,
        learned: form.learned.trim() || null,
        tags: form.tags
          .split(',')
          .map((t) => t.trim())
          .filter(Boolean),
      }),
    onSuccess: onSaved,
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate()
  }

  return (
    <form className="form" onSubmit={submit} aria-label="Editar la entrada">
      <label>
        Tipo
        <select value={form.kind} onChange={(e) => set('kind')(e.target.value as MemoryKind)}>
          {kinds.map((k) => (
            <option key={k.value} value={k.value}>
              {k.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Título
        <input value={form.title} onChange={(e) => set('title')(e.target.value)} required />
      </label>
      <label>
        Tema
        <input
          value={form.topicKey}
          onChange={(e) => set('topicKey')(e.target.value)}
          placeholder="arquitectura/auth"
        />
      </label>
      <label>
        Qué
        <textarea
          value={form.what}
          onChange={(e) => set('what')(e.target.value)}
          rows={5}
          required
        />
      </label>
      <label>
        Por qué
        <textarea value={form.why} onChange={(e) => set('why')(e.target.value)} rows={3} />
      </label>
      <label>
        Dónde
        <textarea value={form.where} onChange={(e) => set('where')(e.target.value)} rows={2} />
      </label>
      <label>
        Qué se aprendió
        <textarea value={form.learned} onChange={(e) => set('learned')(e.target.value)} rows={3} />
      </label>
      <label>
        Etiquetas
        <input
          value={form.tags}
          onChange={(e) => set('tags')(e.target.value)}
          placeholder="ci, gradle"
        />
      </label>
      <p className="small muted">
        Al guardar se crea la versión {o.version + 1}; la anterior se conserva.
      </p>
      <div className="form-actions">
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? 'Guardando…' : 'Guardar versión'}
        </Button>
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancelar
        </Button>
      </div>
      <ErrorMessage error={save.error} />
    </form>
  )
}
