import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Rocket } from 'lucide-react'
import { useState } from 'react'
import { api, type Deployment, type DeploymentEnvironment, type Repository } from '../../api'
import { formatDateTime } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { DataTable, EmptyState, type Column } from '../list/DataTable'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { Sheet } from '../ui/Sheet'
import { CardsSkeleton, LinesSkeleton } from '../ui/Skeleton'
import { DeploymentPills } from './HealthPills'
import { DEPLOYMENT_STATUS, gitHubRepo, PROBE_OUTCOME, shortSha } from './deployment'

/** Despliegues que se leen por repositorio para el historial. */
const HISTORY = 20

const deploymentsKey = (repositoryId: string) => ['deployments', repositoryId] as const

/**
 * Pestaña Despliegues del proyecto (DL-E): por repositorio, qué hay en cada entorno, si está sano
 * y el historial. Cada despliegue abre su detalle con los estados recibidos y la evidencia de la
 * comprobación de salud.
 */
export function ProjectDeployments({
  repositories,
  editable,
}: {
  repositories: Repository[] | undefined
  editable: boolean
}) {
  if (!repositories) return <CardsSkeleton label="los despliegues" />
  const active = repositories.filter((r) => r.archivedAt == null)
  if (active.length === 0)
    return (
      <EmptyState icon={Rocket} title="Ningún repositorio registrado.">
        <p>Los despliegues se ven por repositorio.</p>
      </EmptyState>
    )
  return (
    <div className="deploy-repos">
      {active.map((r) => (
        <RepositoryDeployments key={r.id} repository={r} editable={editable} />
      ))}
    </div>
  )
}

function RepositoryDeployments({
  repository: r,
  editable,
}: {
  repository: Repository
  editable: boolean
}) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState<string | null>(null)
  const environments = useQuery({
    queryKey: [...deploymentsKey(r.id), 'environments'],
    queryFn: () => api.deploymentEnvironments(r.id),
    refetchInterval: 15_000,
  })
  const history = useQuery({
    queryKey: [...deploymentsKey(r.id), 'history'],
    queryFn: () => api.deployments(r.id, HISTORY),
    refetchInterval: 15_000,
  })
  const reconcile = useMutation({
    mutationFn: () => api.reconcileDeployments(r.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: deploymentsKey(r.id) }),
  })
  const repo = gitHubRepo(r.remoteUrl)
  const columns: Column<Deployment>[] = [
    {
      id: 'version',
      header: 'Versión',
      hideable: false,
      cell: (d) => (
        <button
          type="button"
          className="btn-link"
          onClick={() => setOpen(d.id)}
          aria-label={`Detalle del despliegue ${shortSha(d.commitSha)} en ${d.environment}`}
        >
          <code>{shortSha(d.commitSha)}</code>
          {d.ref && <span className="muted"> {d.ref}</span>}
        </button>
      ),
    },
    { id: 'environment', header: 'Entorno', cell: (d) => d.environment },
    { id: 'status', header: 'Estado y salud', cell: (d) => <DeploymentPills deployment={d} /> },
    {
      id: 'pr',
      header: 'PR',
      cell: (d) => <PullRequest repo={repo} number={d.pullRequestNumber} />,
    },
    { id: 'requested', header: 'Pedido', cell: (d) => formatDateTime(d.requestedAt) },
  ]
  const envs = environments.data ?? []
  return (
    <section className="card deploy-repo" aria-labelledby={`deploy-${r.id}`}>
      <div className="section-head">
        <h2 id={`deploy-${r.id}`}>{r.name}</h2>
        {editable && repo && (
          <Button
            size="sm"
            variant="secondary"
            disabled={reconcile.isPending}
            aria-label={`Reconciliar los despliegues de ${r.name} con GitHub`}
            onClick={() => reconcile.mutate()}
          >
            {reconcile.isPending ? 'Reconciliando…' : 'Reconciliar con GitHub'}
          </Button>
        )}
      </div>
      {reconcile.data && (
        <p className="small muted" role="status">
          GitHub: {reconcile.data.deployments} despliegues y {reconcile.data.statuses} estados
          revisados, {reconcile.data.changed}{' '}
          {reconcile.data.changed === 1 ? 'cambio aplicado' : 'cambios aplicados'}.
        </p>
      )}
      <ErrorMessage error={reconcile.error} />
      <ErrorMessage error={environments.error} />
      {environments.isPending && <LinesSkeleton label={`los entornos de ${r.name}`} lines={2} />}
      {environments.data && envs.length === 0 && (
        <p className="muted small">
          Aún no ha llegado ningún despliegue. Llegan de GitHub Deployments cuando los listeners de
          Hermes avisan de que despliegan.
        </p>
      )}
      {envs.length > 0 && (
        <div className="deploy-envs">
          {envs.map((e) => (
            <EnvironmentCard key={e.environment} env={e} repo={repo} onOpen={setOpen} />
          ))}
        </div>
      )}
      <ErrorMessage error={history.error} />
      {history.data && history.data.length > 0 && (
        <>
          <h3 className="deploy-subtitle">Historial</h3>
          <DataTable
            label={`Despliegues de ${r.name}`}
            columns={columns}
            rows={history.data}
            rowKey={(d) => d.id}
            empty={null}
          />
        </>
      )}
      <Sheet
        open={open != null}
        onOpenChange={(o) => !o && setOpen(null)}
        title="Despliegue"
        description={`${r.name}: cómo terminó, si está sano y con qué evidencia.`}
      >
        {open && <DeploymentDetailView id={open} repo={repo} editable={editable} />}
      </Sheet>
    </section>
  )
}

/** Un entorno: lo desplegado ahora, con su salud, y el último pedido si es otro. */
function EnvironmentCard({
  env,
  repo,
  onOpen,
}: {
  env: DeploymentEnvironment
  repo: string | null
  onOpen: (id: string) => void
}) {
  const d = env.deployed
  const latest = env.latest && env.latest.id !== d?.id ? env.latest : null
  return (
    <article className="deploy-env" aria-label={`Entorno ${env.environment}`}>
      <h3>{env.environment}</h3>
      {d ? (
        <>
          <p>
            <button
              type="button"
              className="btn-link"
              onClick={() => onOpen(d.id)}
              aria-label={`Detalle de lo desplegado en ${env.environment}`}
            >
              <code>{shortSha(d.commitSha)}</code>
            </button>
            {d.ref && <span className="muted"> {d.ref}</span>}{' '}
            <PullRequest repo={repo} number={d.pullRequestNumber} />
          </p>
          <DeploymentPills deployment={d} />
          <p className="small muted">
            Desplegado {formatDateTime(d.finishedAt)}
            {d.healthCheckedAt && ` · salud comprobada ${formatDateTime(d.healthCheckedAt)}`}
          </p>
          {d.healthDetail && <p className="small">{d.healthDetail}</p>}
          {d.url && (
            <p className="small">
              <a href={d.url} target="_blank" rel="noreferrer">
                {d.url}
              </a>
            </p>
          )}
        </>
      ) : (
        <p className="muted small">Nada desplegado con éxito todavía.</p>
      )}
      {latest && (
        <div className="deploy-latest">
          <p className="small">
            Último pedido:{' '}
            <button
              type="button"
              className="btn-link"
              onClick={() => onOpen(latest.id)}
              aria-label={`Detalle del último pedido en ${env.environment}`}
            >
              <code>{shortSha(latest.commitSha)}</code>
            </button>{' '}
            <span className="muted">{formatDateTime(latest.requestedAt)}</span>
          </p>
          <DeploymentPills deployment={latest} />
        </div>
      )}
    </article>
  )
}

function PullRequest({ repo, number }: { repo: string | null; number: number | null }) {
  if (number == null) return null
  return repo ? (
    <a href={`https://github.com/${repo}/pull/${number}`} target="_blank" rel="noreferrer">
      #{number}
    </a>
  ) : (
    <span>#{number}</span>
  )
}

/** El detalle: datos, la evidencia de salud (de este SHA) y los estados recibidos. */
function DeploymentDetailView({
  id,
  repo,
  editable,
}: {
  id: string
  repo: string | null
  editable: boolean
}) {
  const queryClient = useQueryClient()
  const detail = useQuery({
    queryKey: ['deployment', id],
    queryFn: () => api.deployment(id),
    refetchInterval: (q) => (q.state.data?.deployment.health === 'CHECKING' ? 3_000 : 15_000),
  })
  const recheck = useMutation({
    mutationFn: () => api.recheckDeployment(id),
    onSuccess: (d) => {
      void queryClient.invalidateQueries({ queryKey: ['deployment', id] })
      void queryClient.invalidateQueries({ queryKey: deploymentsKey(d.repositoryId) })
    },
  })
  if (detail.error) return <ErrorMessage error={detail.error} />
  if (!detail.data) return <LinesSkeleton label="el despliegue" />
  const { deployment: d, statuses, probes } = detail.data
  return (
    <div className="deploy-detail">
      <DeploymentPills deployment={d} />
      <dl className="repo-facts small">
        <div>
          <dt>SHA</dt>
          <dd>
            <code>{d.commitSha}</code>
          </dd>
        </div>
        <div>
          <dt>Entorno</dt>
          <dd>{d.environment}</dd>
        </div>
        {d.ref && (
          <div>
            <dt>Rama</dt>
            <dd>{d.ref}</dd>
          </div>
        )}
        {d.pullRequestNumber != null && (
          <div>
            <dt>PR</dt>
            <dd>
              <PullRequest repo={repo} number={d.pullRequestNumber} />
            </dd>
          </div>
        )}
        {d.url && (
          <div>
            <dt>URL</dt>
            <dd>
              <a href={d.url} target="_blank" rel="noreferrer">
                {d.url}
              </a>
            </dd>
          </div>
        )}
        {d.creator && (
          <div>
            <dt>Quién</dt>
            <dd>{d.creator}</dd>
          </div>
        )}
        <div>
          <dt>Pedido</dt>
          <dd>{formatDateTime(d.requestedAt)}</dd>
        </div>
        {d.startedAt && (
          <div>
            <dt>Empezó</dt>
            <dd>{formatDateTime(d.startedAt)}</dd>
          </div>
        )}
        {d.finishedAt && (
          <div>
            <dt>Terminó</dt>
            <dd>{formatDateTime(d.finishedAt)}</dd>
          </div>
        )}
        {d.stuckAt && (
          <div>
            <dt>Atascado</dt>
            <dd>desde {formatDateTime(d.stuckAt)}, sin estados nuevos</dd>
          </div>
        )}
        {d.error && (
          <div>
            <dt>Error</dt>
            <dd>{d.error}</dd>
          </div>
        )}
      </dl>

      <section aria-labelledby="deploy-health-title">
        <div className="repo-section-head">
          <h3 id="deploy-health-title">Salud de {shortSha(d.commitSha)}</h3>
          {editable && d.status === 'SUCCEEDED' && (
            <Button
              size="sm"
              variant="secondary"
              disabled={recheck.isPending || d.health === 'CHECKING'}
              onClick={() => recheck.mutate()}
            >
              Volver a comprobar
            </Button>
          )}
        </div>
        <ErrorMessage error={recheck.error} />
        {d.healthDetail && <p className="small">{d.healthDetail}</p>}
        {probes.length === 0 ? (
          <p className="muted small">
            {d.status === 'SUCCEEDED'
              ? 'Sin intentos: el entorno no tiene comprobación de salud o aún no ha empezado.'
              : 'Solo se comprueba lo desplegado con éxito.'}
          </p>
        ) : (
          <ol className="deploy-probes" aria-label="Intentos de la comprobación">
            {probes.map((p) => (
              <li key={p.attempt}>
                <Pill tone={PROBE_OUTCOME[p.outcome].tone}>
                  Intento {p.attempt}: {PROBE_OUTCOME[p.outcome].label}
                </Pill>
                <span className="small">
                  {p.statusCode != null && `HTTP ${p.statusCode} · `}
                  {p.latencyMs != null && `${p.latencyMs} ms · `}
                  {formatDateTime(p.checkedAt)}
                </span>
                {p.detail && <span className="small muted">{p.detail}</span>}
              </li>
            ))}
          </ol>
        )}
      </section>

      <section aria-labelledby="deploy-statuses-title">
        <h3 id="deploy-statuses-title">Estados recibidos</h3>
        {statuses.length === 0 ? (
          <p className="muted small">Ninguno todavía.</p>
        ) : (
          <ol className="deploy-statuses">
            {statuses.map((s) => (
              <li key={s.id}>
                <span>
                  <strong>{DEPLOYMENT_STATUS[s.state].label}</strong> ·{' '}
                  {formatDateTime(s.occurredAt)}
                  {!s.applied && <span className="muted"> · llegó tarde, no cambió nada</span>}
                </span>
                {s.description && <span className="small muted">{s.description}</span>}
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  )
}
