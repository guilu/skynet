import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Layers, Zap } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, type Repository, type TriggerKind, type TriggerRule } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { formatDateTime } from '../../format'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { Sheet } from '../ui/Sheet'

const triggersKey = (repositoryId: string) => ['triggers', 'repository', repositoryId] as const
const queueKey = (repositoryId: string) => ['run-queue', repositoryId] as const

const OUTCOME: Record<string, { tone: 'ok' | 'neutral' | 'bad'; label: string }> = {
  LAUNCHED: { tone: 'ok', label: 'lanzó' },
  SKIPPED: { tone: 'neutral', label: 'se saltó' },
  REJECTED: { tone: 'bad', label: 'no lanzó' },
}

/**
 * En la ficha de un repositorio (DS-C): cuántas ejecuciones a la vez admite y sus disparadores,
 * los de GitHub (etiqueta o comentario) y los programados, con su próxima hora.
 */
export function RepositoryTriggers({
  repository: r,
  editable,
}: {
  repository: Repository
  editable: boolean
}) {
  return (
    <>
      <QueueSection repository={r} editable={editable} />
      <TriggersSection repository={r} editable={editable} />
    </>
  )
}

function QueueSection({ repository: r, editable }: { repository: Repository; editable: boolean }) {
  const queryClient = useQueryClient()
  const queue = useQuery({ queryKey: queueKey(r.id), queryFn: () => api.runQueue(r.id) })
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')
  const save = useMutation({
    mutationFn: (limit: number | null) => api.saveRunQueue(r.id, limit),
    onSuccess: (saved) => {
      queryClient.setQueryData(queueKey(r.id), saved)
      setEditing(false)
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate(value.trim() === '' ? null : Number(value))
  }
  const q = queue.data
  return (
    <section className="repo-section" aria-label={`Ejecuciones a la vez en ${r.name}`}>
      <div className="repo-section-head">
        <h3>
          <Layers size={18} strokeWidth={2.5} aria-hidden="true" /> Ejecuciones a la vez{' '}
          {q && (
            <span className={q.custom ? 'tag tag-on' : 'tag'}>
              {q.custom ? 'propio' : 'general'}
            </span>
          )}
        </h3>
        {editable && q && !editing && (
          <Button
            size="sm"
            variant="secondary"
            aria-label={`Cambiar las ejecuciones a la vez de ${r.name}`}
            onClick={() => {
              setValue(q.custom ? String(q.maxConcurrentRuns) : '')
              setEditing(true)
            }}
          >
            Cambiar
          </Button>
        )}
      </div>
      <ErrorMessage error={queue.error} />
      {q && !editing && (
        <p className="small">
          Como mucho <strong>{q.maxConcurrentRuns}</strong> · ahora {q.running} en marcha
          {q.queued.length > 0 && (
            <>
              {' '}
              y <strong>{q.queued.length}</strong> en cola (
              <Link to={`/runs/${q.queued[0]}`}>la siguiente</Link>)
            </>
          )}
          <span className="muted">
            . Las que no caben esperan; una puerta que espera tu decisión no ocupa hueco.
          </span>
        </p>
      )}
      {q && editing && (
        <form className="form form-inline" onSubmit={submit}>
          <label>
            Ejecuciones a la vez
            <input
              type="number"
              min={1}
              max={50}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={`${q.defaultLimit} (el general)`}
            />
            <span className="hint">Vacío: el general ({q.defaultLimit}).</span>
          </label>
          <div className="form-actions">
            <Button type="submit" size="sm" disabled={save.isPending}>
              Guardar
            </Button>
            <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>
              Cancelar
            </Button>
          </div>
          <ErrorMessage error={save.error} />
        </form>
      )}
    </section>
  )
}

function TriggersSection({
  repository: r,
  editable,
}: {
  repository: Repository
  editable: boolean
}) {
  const [adding, setAdding] = useState(false)
  const triggers = useQuery({
    queryKey: triggersKey(r.id),
    queryFn: () => api.repositoryTriggers(r.id),
  })
  return (
    <section className="repo-section" aria-label={`Disparadores de ${r.name}`}>
      <div className="repo-section-head">
        <h3>
          <Zap size={18} strokeWidth={2.5} aria-hidden="true" /> Disparadores
        </h3>
        {editable && (
          <Button
            size="sm"
            variant="secondary"
            aria-label={`Nuevo disparador en ${r.name}`}
            onClick={() => setAdding(true)}
          >
            Nuevo disparador
          </Button>
        )}
      </div>
      <ErrorMessage error={triggers.error} />
      {triggers.data?.length === 0 && (
        <p className="muted small">
          Ninguno: este repositorio solo se lanza desde la web. Los disparadores se encienden en{' '}
          <Link to="/settings?tab=github">Ajustes → GitHub</Link>.
        </p>
      )}
      {triggers.data && triggers.data.length > 0 && (
        <ul className="trigger-list">
          {triggers.data.map((t) => (
            <TriggerItem key={t.id} rule={t} editable={editable} />
          ))}
        </ul>
      )}
      <Sheet
        open={adding}
        onOpenChange={(open) => !open && setAdding(false)}
        title={`Nuevo disparador en ${r.name}`}
        description="Qué workflow se puede lanzar desde fuera de la web: con una etiqueta o un comentario en GitHub, o a una hora."
      >
        <NewTriggerForm repositoryId={r.id} onDone={() => setAdding(false)} />
      </Sheet>
    </section>
  )
}

function TriggerItem({ rule: t, editable }: { rule: TriggerRule; editable: boolean }) {
  const queryClient = useQueryClient()
  const refresh = () => queryClient.invalidateQueries({ queryKey: triggersKey(t.repositoryId) })
  const toggle = useMutation({
    mutationFn: () => api.updateTrigger(t.id, { enabled: !t.enabled }),
    onSuccess: refresh,
  })
  const remove = useMutation({ mutationFn: () => api.deleteTrigger(t.id), onSuccess: refresh })
  const fire = useMutation({ mutationFn: () => api.fireTrigger(t.id), onSuccess: refresh })
  const s = t.schedule
  const outcome = s?.lastOutcome ? OUTCOME[s.lastOutcome] : null
  return (
    <li className="trigger-item">
      <div className="trigger-main">
        <p className="small">
          {t.kind === 'ISSUE' ? (
            <>
              <code>{t.label}</code> o <code>/skynet {t.workflowKey}</code> lanzan{' '}
              <strong>{t.workflowKey}</strong>
            </>
          ) : (
            <>
              <code>{s?.cron}</code> <span className="muted">({s?.timeZone})</span> lanza{' '}
              <strong>{t.workflowKey}</strong> en «{s?.workItemTitle}»
            </>
          )}{' '}
          {!t.enabled && <Pill tone="neutral">Desactivado</Pill>}
        </p>
        {s && (
          <p className="muted small">
            {t.enabled && s.nextFireAt
              ? `Próxima: ${formatDateTime(s.nextFireAt)}`
              : 'Sin próxima hora'}
            {s.lastFiredAt && outcome && (
              <>
                {' · '}
                Última: {formatDateTime(s.lastFiredAt)},{' '}
                <Pill tone={outcome.tone}>{outcome.label}</Pill>
                {s.lastReason && <> {s.lastReason}</>}
                {s.lastRunId && (
                  <>
                    {' '}
                    (<Link to={`/runs/${s.lastRunId}`}>ver</Link>)
                  </>
                )}
              </>
            )}
          </p>
        )}
        {Object.keys(t.inputPolicy).length > 0 && (
          <p className="muted small">La issue puede dar: {Object.keys(t.inputPolicy).join(', ')}</p>
        )}
      </div>
      {editable && (
        <div className="trigger-actions">
          {t.kind === 'SCHEDULE' && t.enabled && (
            <Button
              size="sm"
              variant="secondary"
              disabled={fire.isPending}
              onClick={() => fire.mutate()}
            >
              Disparar ya
            </Button>
          )}
          <Button
            size="sm"
            variant="secondary"
            disabled={toggle.isPending}
            onClick={() => toggle.mutate()}
          >
            {t.enabled ? 'Desactivar' : 'Activar'}
          </Button>
          <Button
            size="sm"
            variant="secondary-danger"
            aria-label={`Borrar el disparador de ${t.workflowKey}`}
            disabled={remove.isPending}
            onClick={() => remove.mutate()}
          >
            Borrar
          </Button>
        </div>
      )}
      <ErrorMessage error={toggle.error ?? remove.error ?? fire.error} />
    </li>
  )
}

function NewTriggerForm({ repositoryId, onDone }: { repositoryId: string; onDone: () => void }) {
  const queryClient = useQueryClient()
  const workflows = useQuery({ queryKey: ['workflows'], queryFn: () => api.workflows() })
  const [kind, setKind] = useState<TriggerKind>('ISSUE')
  const [workflowKey, setWorkflowKey] = useState('')
  const [label, setLabel] = useState('')
  const [cron, setCron] = useState('0 0 9 * * MON')
  const [title, setTitle] = useState('')
  const create = useMutation({
    mutationFn: () =>
      api.createTrigger(
        repositoryId,
        kind === 'ISSUE'
          ? { kind, workflowKey, label: label.trim() || undefined }
          : { kind, workflowKey, cron, workItemTitle: title.trim() || undefined },
      ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: triggersKey(repositoryId) })
      onDone()
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }
  const published = (workflows.data ?? []).filter((w) => w.key !== 'adhoc')
  return (
    <form className="form" onSubmit={submit}>
      <fieldset className="form-choice">
        <legend>Cómo se lanza</legend>
        <label>
          <input
            type="radio"
            name="trigger-kind"
            checked={kind === 'ISSUE'}
            onChange={() => setKind('ISSUE')}
          />{' '}
          Desde GitHub: una etiqueta en una issue o <code>/skynet &lt;workflow&gt;</code>
        </label>
        <label>
          <input
            type="radio"
            name="trigger-kind"
            checked={kind === 'SCHEDULE'}
            onChange={() => setKind('SCHEDULE')}
          />{' '}
          A una hora (cron), siempre sobre el mismo trabajo
        </label>
      </fieldset>
      <label>
        Workflow
        <select value={workflowKey} onChange={(e) => setWorkflowKey(e.target.value)} required>
          <option value="">Elige uno publicado…</option>
          {published.map((w) => (
            <option key={w.key} value={w.key}>
              {w.name ?? w.key} ({w.key})
            </option>
          ))}
        </select>
      </label>
      {kind === 'ISSUE' ? (
        <label>
          Etiqueta (opcional)
          <input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={`skynet:${workflowKey || '<workflow>'}`}
          />
          <span className="hint">
            Empieza por skynet:. Lanzan quienes están autorizados en Ajustes → GitHub.
          </span>
        </label>
      ) : (
        <>
          <label>
            Cuándo (cron de seis campos, con segundos)
            <input value={cron} onChange={(e) => setCron(e.target.value)} required />
            <span className="hint">
              <code>0 0 9 * * MON</code>: los lunes a las 9:00 (hora de Madrid). Si la anterior
              sigue en marcha, ese disparo se salta.
            </span>
          </label>
          <label>
            Título del trabajo (opcional)
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={`${workflowKey || '<workflow>'} (programado)`}
            />
          </label>
        </>
      )}
      <div className="form-actions">
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? 'Creando…' : 'Crear disparador'}
        </Button>
      </div>
      <ErrorMessage error={create.error} />
    </form>
  )
}
