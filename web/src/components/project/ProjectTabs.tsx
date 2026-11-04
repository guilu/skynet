import { useQuery } from '@tanstack/react-query'
import { CirclePlay, GitPullRequest, Workflow } from 'lucide-react'
import { Link } from 'react-router'
import { api, type ChangeView, type Run } from '../../api'
import { formatDateTime } from '../../format'
import { useNow } from '../../useNow'
import { ErrorMessage } from '../ErrorMessage'
import { DataTable, EmptyState, type Column } from '../list/DataTable'
import { RunsTable } from '../RunsTable'
import { Pill } from '../ui/Pill'
import { TableSkeleton } from '../ui/Skeleton'
import { CHANGE_STATUS, formatUsd } from '../work/phase'

/** Ejecuciones que se leen para las pestañas Ejecuciones y Workflows. */
const RUNS_SHOWN = 50

function useProjectRuns(projectId: string) {
  return useQuery({
    queryKey: ['runs', 'list', 'project', projectId],
    queryFn: () => api.listRuns({ projectId, size: RUNS_SHOWN }),
    refetchInterval: 10_000,
  })
}

/** Las ejecuciones (intentos) de todos los trabajos del proyecto. */
export function ProjectRuns({ projectId }: { projectId: string }) {
  const runs = useProjectRuns(projectId)
  const now = useNow(true)
  return (
    <>
      <ErrorMessage error={runs.error} />
      {runs.isPending && <TableSkeleton label="las ejecuciones" />}
      {runs.data && (
        <>
          <RunsTable
            runs={runs.data.items}
            now={now}
            label="Ejecuciones del proyecto"
            empty={
              <EmptyState icon={CirclePlay} title="Aún no hay ejecuciones.">
                <p>Cada ejecución es un intento de conseguir un trabajo con un workflow.</p>
              </EmptyState>
            }
          />
          {runs.data.total > runs.data.items.length && (
            <p className="muted small">
              Las {runs.data.items.length} más recientes de {runs.data.total}.{' '}
              <Link to={`/runs?projectId=${projectId}`}>Ver todas</Link>
            </p>
          )}
        </>
      )}
    </>
  )
}

const CHANGE_COLUMNS: Column<ChangeView>[] = [
  {
    id: 'pr',
    header: 'PR',
    hideable: false,
    cell: (c) =>
      c.url ? (
        <a href={c.url} target="_blank" rel="noreferrer">
          {c.number != null ? `#${c.number}` : 'PR'} {c.title}
        </a>
      ) : (
        `${c.number != null ? `#${c.number} ` : ''}${c.title}`
      ),
  },
  {
    id: 'work',
    header: 'Trabajo',
    cell: (c) => (
      <Link to={`/work-items/${c.workItemId}`} className="row-link">
        <span className="key">{c.workItemKey}</span> {c.workItemTitle}
      </Link>
    ),
  },
  { id: 'repo', header: 'Repositorio', cell: (c) => c.repositoryName },
  {
    id: 'status',
    header: 'Estado',
    cell: (c) => (
      <Pill tone={c.status === 'MERGED' ? 'ok' : c.status === 'OPEN' ? 'active' : 'neutral'}>
        {CHANGE_STATUS[c.status]}
      </Pill>
    ),
  },
  {
    id: 'run',
    header: 'Intento',
    cell: (c) => <Link to={`/runs/${c.workflowRunId}`}>Ver ejecución</Link>,
  },
  { id: 'updated', header: 'Actualizada', cell: (c) => formatDateTime(c.updatedAt) },
]

/** Las PRs que ha producido la fábrica en este proyecto, con su trabajo y su intento. */
export function ProjectChanges({ projectId }: { projectId: string }) {
  const changes = useQuery({
    queryKey: ['project', projectId, 'changes'],
    queryFn: () => api.projectChanges(projectId),
    refetchInterval: 30_000,
  })
  return (
    <>
      <ErrorMessage error={changes.error} />
      {changes.isPending && <TableSkeleton label="los cambios" />}
      {changes.data && (
        <DataTable
          label="Cambios"
          columns={CHANGE_COLUMNS}
          rows={changes.data}
          rowKey={(c) => c.id}
          empty={
            <EmptyState icon={GitPullRequest} title="Aún no hay PRs.">
              <p>Las PRs que abran los agentes de este proyecto saldrán aquí.</p>
            </EmptyState>
          }
        />
      )}
    </>
  )
}

interface WorkflowUse {
  key: string
  name: string
  runs: number
  finished: number
  succeeded: number
  costUsd: number | null
  lastAt: string
}

/** Agrupa las ejecuciones por workflow: veces, tasa de éxito, coste y último uso. */
function workflowUses(runs: Run[]): WorkflowUse[] {
  const map = new Map<string, WorkflowUse>()
  for (const r of runs) {
    if (!r.workflow) continue
    const u = map.get(r.workflow.key) ?? {
      key: r.workflow.key,
      name: r.workflow.name ?? r.workflow.key,
      runs: 0,
      finished: 0,
      succeeded: 0,
      costUsd: null,
      lastAt: r.createdAt,
    }
    u.runs += 1
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(r.status)) u.finished += 1
    if (r.status === 'SUCCEEDED') u.succeeded += 1
    if (r.totals.costUsd != null) u.costUsd = (u.costUsd ?? 0) + r.totals.costUsd
    if (Date.parse(r.createdAt) > Date.parse(u.lastAt)) u.lastAt = r.createdAt
    map.set(r.workflow.key, u)
  }
  return [...map.values()].sort((a, b) => b.runs - a.runs)
}

const WORKFLOW_COLUMNS: Column<WorkflowUse>[] = [
  {
    id: 'workflow',
    header: 'Workflow',
    hideable: false,
    cell: (w) => (
      <Link to={`/workflows/${w.key}`} className="row-link">
        {w.name}
      </Link>
    ),
  },
  { id: 'runs', header: 'Veces', cell: (w) => w.runs },
  {
    id: 'success',
    header: 'Éxito',
    cell: (w) =>
      w.finished > 0
        ? `${Math.round((w.succeeded / w.finished) * 100)} % (${w.succeeded} de ${w.finished})`
        : '—',
  },
  { id: 'cost', header: 'Coste', cell: (w) => formatUsd(w.costUsd) },
  { id: 'last', header: 'Último uso', cell: (w) => formatDateTime(w.lastAt) },
]

/** Los workflows usados en el proyecto (en sus últimas ejecuciones), con veces y tasa de éxito. */
export function ProjectWorkflows({ projectId }: { projectId: string }) {
  const runs = useProjectRuns(projectId)
  return (
    <>
      <ErrorMessage error={runs.error} />
      {runs.isPending && <TableSkeleton label="los workflows" />}
      {runs.data && (
        <>
          <DataTable
            label="Workflows usados"
            columns={WORKFLOW_COLUMNS}
            rows={workflowUses(runs.data.items)}
            rowKey={(w) => w.key}
            empty={
              <EmptyState icon={Workflow} title="Aún no se ha usado ningún workflow.">
                <p>Un workflow es el procedimiento con el que se consigue un trabajo.</p>
              </EmptyState>
            }
          />
          {runs.data.total > runs.data.items.length && (
            <p className="muted small">
              Cuenta las {runs.data.items.length} ejecuciones más recientes.
            </p>
          )}
        </>
      )}
    </>
  )
}
