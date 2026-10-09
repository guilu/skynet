import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CirclePlay, Play } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, WORK_ITEM_TYPES, type Repository, type Run } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { runColumns } from '../components/runColumns'
import { StatusBadge } from '../components/StatusBadge'
import { Button } from '../components/ui/Button'
import { Sheet } from '../components/ui/Sheet'
import { formatDateTime } from '../format'
import { useNow } from '../useNow'
import { TableSkeleton } from '../components/ui/Skeleton'

const TYPE_LABEL = Object.fromEntries(WORK_ITEM_TYPES.map((t) => [t.value, t.label]))

/** Columnas de las ejecuciones de un trabajo: la del trabajo sobra, la fecha lleva a la ejecución. */
function columns(now: number): Column<Run>[] {
  return [
    {
      id: 'launched',
      header: 'Lanzada',
      hideable: false,
      cell: (run) => (
        <Link to={`/runs/${run.id}`} className="row-link">
          {formatDateTime(run.createdAt)}
        </Link>
      ),
    },
    ...runColumns(now).filter((c) => c.id !== 'work' && c.id !== 'created'),
  ]
}

/** Trabajo: su descripción, sus ejecuciones y el lanzamiento de un agente en un panel lateral. */
export function WorkItemPage() {
  const { workItemId = '' } = useParams()
  const [launching, setLaunching] = useState(false)
  const item = useQuery({
    queryKey: ['work-item', workItemId],
    queryFn: () => api.workItem(workItemId),
  })
  const projectId = item.data?.projectId
  const repos = useQuery({
    queryKey: ['repositories', projectId],
    queryFn: () => api.repositories(projectId!),
    enabled: !!projectId,
  })
  const runs = useQuery({ queryKey: ['runs', workItemId], queryFn: () => api.runs(workItemId) })
  const now = useNow(runs.data?.some((r) => r.finishedAt == null) ?? false)

  return (
    <>
      <ErrorMessage error={item.error} />
      <div className="page-head">
        <div className="page-title">
          <h1>
            {item.data ? `${item.data.key} · ${item.data.title}` : 'Trabajo'}{' '}
            {item.data && <StatusBadge status={item.data.status} />}
          </h1>
          {item.data && (
            <p className="muted small">
              {TYPE_LABEL[item.data.type] ?? item.data.type} · creado el{' '}
              {formatDateTime(item.data.createdAt)}
              {item.data.externalRef && <> · {item.data.externalRef}</>}
            </p>
          )}
        </div>
        <div className="page-actions">
          <Button onClick={() => setLaunching(true)} disabled={!item.data}>
            <Play size={18} strokeWidth={2.75} aria-hidden="true" />
            Lanzar agente
          </Button>
        </div>
      </div>
      {item.data?.description && (
        <section className="card description-card" aria-label="Descripción">
          <p className="prewrap">{item.data.description}</p>
        </section>
      )}

      <section aria-labelledby="runs-title">
        <h2 id="runs-title">Ejecuciones</h2>
        <ErrorMessage error={runs.error} />
        {runs.isPending && <TableSkeleton label="las ejecuciones" columns={6} rows={3} />}
        {runs.data && (
          <DataTable
            label="Ejecuciones del trabajo"
            columns={columns(now)}
            rows={runs.data}
            rowKey={(r) => r.id}
            empty={
              <EmptyState icon={CirclePlay} title="Todavía no se ha lanzado ninguna.">
                <Button size="sm" onClick={() => setLaunching(true)} disabled={!item.data}>
                  Lanzar el primer agente
                </Button>
              </EmptyState>
            }
          />
        )}
      </section>

      <Sheet
        open={launching}
        onOpenChange={setLaunching}
        title="Lanzar agente"
        description="El agente queda en cola hasta que un runner conectado lo recoge."
      >
        {repos.data?.length === 0 ? (
          <p className="muted">
            Registra antes un repositorio en el{' '}
            <Link to={`/projects/${projectId}?tab=repos`}>proyecto</Link>.
          </p>
        ) : (
          <LaunchForm workItemId={workItemId} repositories={repos.data ?? []} />
        )}
      </Sheet>
    </>
  )
}

function LaunchForm({
  workItemId,
  repositories,
}: {
  workItemId: string
  repositories: Repository[]
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [repositoryId, setRepositoryId] = useState('')
  const [prompt, setPrompt] = useState('')
  const [maxTurns, setMaxTurns] = useState('')
  const [maxBudgetUsd, setMaxBudgetUsd] = useState('')
  const [timeoutMinutes, setTimeoutMinutes] = useState('')
  const repository = repositories.find((r) => r.id === repositoryId) ?? repositories[0]
  const policy = repository?.agentPolicy
  const launch = useMutation({
    mutationFn: () =>
      api.launchRun(workItemId, {
        repositoryId: repository!.id,
        prompt,
        maxTurns: optionalNumber(maxTurns),
        maxBudgetUsd: optionalNumber(maxBudgetUsd),
        timeoutMinutes: optionalNumber(timeoutMinutes),
      }),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: ['runs', workItemId] })
      void navigate(`/runs/${run.id}`)
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    launch.mutate()
  }

  return (
    <form className="form" onSubmit={submit}>
      <label>
        Repositorio
        <select value={repository?.id ?? ''} onChange={(e) => setRepositoryId(e.target.value)}>
          {repositories.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Prompt
        <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} rows={7} required />
      </label>
      {policy && (
        <p className="small muted policy-note">
          Política {repository.agentPolicyCustom ? 'del repositorio' : 'global'}: herramientas{' '}
          {policy.allowedTools.length > 0 ? (
            <code>{policy.allowedTools.join(', ')}</code>
          ) : (
            'ninguna'
          )}
          , modo <code>{policy.permissionMode}</code>, entorno{' '}
          {policy.environment === null
            ? 'el que permite el runner'
            : policy.environment.length > 0
              ? policy.environment.join(', ')
              : 'sin variables extra'}
          .
        </p>
      )}
      <fieldset className="limits">
        <legend>Límites (opcionales; vacío = el máximo del repositorio)</legend>
        <label>
          Turnos máximos{policy?.maxTurns != null && ` (hasta ${policy.maxTurns})`}
          <input
            type="number"
            min={1}
            max={policy?.maxTurns ?? 1000}
            step={1}
            value={maxTurns}
            onChange={(e) => setMaxTurns(e.target.value)}
          />
        </label>
        <label>
          Presupuesto (US$){policy?.maxBudgetUsd != null && ` (hasta ${policy.maxBudgetUsd})`}
          <input
            type="number"
            min={0.01}
            max={policy?.maxBudgetUsd ?? 1000}
            step={0.01}
            value={maxBudgetUsd}
            onChange={(e) => setMaxBudgetUsd(e.target.value)}
          />
        </label>
        <label>
          Tiempo máximo (min)
          {policy?.timeoutMinutes != null && ` (hasta ${policy.timeoutMinutes})`}
          <input
            type="number"
            min={1}
            max={policy?.timeoutMinutes ?? 1440}
            step={1}
            value={timeoutMinutes}
            onChange={(e) => setTimeoutMinutes(e.target.value)}
          />
        </label>
      </fieldset>
      <div className="form-actions">
        <Button type="submit" disabled={launch.isPending || !repository}>
          <Play size={18} strokeWidth={2.75} aria-hidden="true" />
          {launch.isPending ? 'Lanzando…' : 'Lanzar'}
        </Button>
      </div>
      <p className="hint">Si no hay ninguno conectado, mira «Runner local» en el README.</p>
      <ErrorMessage error={launch.error} />
    </form>
  )
}

const optionalNumber = (value: string) => (value.trim() === '' ? undefined : Number(value))
