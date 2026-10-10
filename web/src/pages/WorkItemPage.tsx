import { useQuery } from '@tanstack/react-query'
import { CirclePlay, Play } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, WORK_ITEM_TYPES, type Run } from '../api'
import {
  ArchivedBanner,
  ArchiveMenu,
  ArchivedToggle,
  InheritedArchiveBanner,
} from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { LaunchForm } from '../components/launch/LaunchForm'
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

/**
 * Trabajo: su descripción, sus ejecuciones y el lanzamiento de un workflow en un panel lateral.
 * Archivado (él o su proyecto), no se lanza nada.
 */
export function WorkItemPage() {
  const { workItemId = '' } = useParams()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const showArchived = params.get('archived') === 'true'
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
  const project = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId!),
    enabled: !!projectId,
  })
  const runs = useQuery({
    queryKey: ['runs', workItemId, showArchived],
    queryFn: () => api.runs(workItemId, showArchived ? 'true' : undefined),
  })
  const archivedAt = item.data?.archivedAt ?? null
  const projectArchived = project.data?.archivedAt != null
  const canLaunch = !!item.data && archivedAt == null && !projectArchived
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
          {canLaunch && (
            <Button onClick={() => setLaunching(true)}>
              <Play size={18} strokeWidth={2.75} aria-hidden="true" />
              Lanzar workflow
            </Button>
          )}
          {item.data && (
            <ArchiveMenu
              target={{ kind: 'work-item', id: workItemId }}
              name={`${item.data.key} · ${item.data.title}`}
              archivedAt={archivedAt}
              onDeleted={() => void navigate(`/projects/${item.data.projectId}`)}
            />
          )}
        </div>
      </div>
      {archivedAt && (
        <ArchivedBanner
          target={{ kind: 'work-item', id: workItemId }}
          archivedAt={archivedAt}
          note="Sus ejecuciones no salen en las listas y no se pueden lanzar agentes."
        />
      )}
      {!archivedAt && projectArchived && project.data && (
        <InheritedArchiveBanner>
          El proyecto <Link to={`/projects/${project.data.id}`}>{project.data.key}</Link> está
          archivado: restáuralo para lanzar agentes.
        </InheritedArchiveBanner>
      )}
      {item.data?.description && (
        <section className="card description-card" aria-label="Descripción">
          <p className="prewrap">{item.data.description}</p>
        </section>
      )}

      <section aria-labelledby="runs-title">
        <div className="section-head">
          <h2 id="runs-title">Ejecuciones</h2>
          <ArchivedToggle
            label="Archivadas"
            on={showArchived}
            onChange={(on) => setParams(on ? { archived: 'true' } : {}, { replace: true })}
          />
        </div>
        <ErrorMessage error={runs.error} />
        {runs.isPending && <TableSkeleton label="las ejecuciones" columns={6} rows={3} />}
        {runs.data && (
          <DataTable
            label="Ejecuciones del trabajo"
            columns={columns(now)}
            rows={runs.data}
            rowKey={(r) => r.id}
            empty={
              showArchived ? (
                <EmptyState icon={CirclePlay} title="No hay ejecuciones archivadas." />
              ) : (
                <EmptyState icon={CirclePlay} title="Todavía no se ha lanzado ninguna.">
                  {canLaunch && (
                    <Button size="sm" onClick={() => setLaunching(true)}>
                      Lanzar el primero
                    </Button>
                  )}
                </EmptyState>
              )
            }
          />
        )}
      </section>

      <Sheet
        open={launching}
        onOpenChange={setLaunching}
        title="Lanzar workflow"
        description="Cada fase con agente queda en cola hasta que un runner conectado la recoge."
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
