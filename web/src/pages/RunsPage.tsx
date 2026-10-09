import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { CirclePlay } from 'lucide-react'
import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { api, type RunStatus } from '../api'
import { ArchivedToggle, BulkRunActions } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { ColumnsMenu, EmptyState } from '../components/list/DataTable'
import { useHiddenColumns } from '../components/list/columns'
import { Pager } from '../components/list/Pager'
import {
  FilterChips,
  ListToolbar,
  RemovableChip,
  SearchField,
  type ChipOption,
} from '../components/list/Toolbar'
import { RunsTable } from '../components/RunsTable'
import { runColumns } from '../components/runColumns'
import { Button } from '../components/ui/Button'
import { formatDateTime } from '../format'
import { useNow } from '../useNow'
import { TableSkeleton } from '../components/ui/Skeleton'

const PAGE_SIZE = 25

/** Estados por los que se filtra; los valores van a la URL tal cual (`?status=FAILED`). */
const STATUSES: (ChipOption & { value: RunStatus })[] = [
  { value: 'PENDING', label: 'En cola', tone: 'neutral' },
  { value: 'RUNNING', label: 'En curso', tone: 'active' },
  { value: 'SUCCEEDED', label: 'Completadas', tone: 'ok' },
  { value: 'FAILED', label: 'Fallidas', tone: 'bad' },
  { value: 'CANCELLED', label: 'Canceladas', tone: 'neutral' },
]

/**
 * Todas las ejecuciones: búsqueda por clave o título del trabajo, estados, proyecto, fecha de
 * creación, archivadas y página, todo en la URL para poder enlazarlo. Las filas se pueden
 * seleccionar para archivarlas, restaurarlas o eliminarlas de una vez.
 */
export function RunsPage() {
  const [params, setParams] = useSearchParams()
  const status = params.getAll('status') as RunStatus[]
  const since = params.get('since') ?? undefined
  const projectId = params.get('projectId') ?? undefined
  const q = params.get('q') ?? ''
  const archived = params.get('archived') === 'true'
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const runs = useQuery({
    queryKey: ['runs', 'list', status.join(','), projectId, since, q, archived, page],
    queryFn: () =>
      api.listRuns({
        status,
        projectId,
        since,
        q,
        archived: archived ? 'true' : undefined,
        page,
        size: PAGE_SIZE,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
  })
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.projects() })
  const now = useNow(true)
  const columns = runColumns(now)
  const [hidden, toggleColumn] = useHiddenColumns('runs')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const selectedRuns = (runs.data?.items ?? [])
    .filter((r) => selected.has(r.id))
    .map((r) => ({
      id: r.id,
      name: `${r.workItemKey ?? 'Ejecución'} · ${formatDateTime(r.createdAt)}`,
    }))

  /** Cambia los filtros indicados y vuelve a la primera página. */
  const go = (
    next: {
      status?: RunStatus[]
      since?: string | null
      projectId?: string | null
      q?: string
      archived?: boolean
      page?: number
    } = {},
  ) => {
    setSelected(new Set())
    const p = new URLSearchParams()
    for (const s of next.status ?? status) p.append('status', s)
    const project = next.projectId === undefined ? projectId : next.projectId
    if (project) p.set('projectId', project)
    const from = next.since === undefined ? since : next.since
    if (from) p.set('since', from)
    const text = next.q ?? q
    if (text) p.set('q', text)
    if (next.archived ?? archived) p.set('archived', 'true')
    if (next.page) p.set('page', String(next.page))
    setParams(p)
  }
  const filtered = status.length > 0 || !!since || !!projectId || !!q || archived

  return (
    <>
      <h1>Ejecuciones</h1>
      <ListToolbar>
        <SearchField
          label="Buscar ejecuciones"
          placeholder="Clave o título del trabajo"
          value={q}
          onChange={(text) => go({ q: text })}
        />
        <FilterChips
          label="Estado"
          options={STATUSES}
          selected={status}
          onChange={(s) => go({ status: s as RunStatus[] })}
        />
        {!!projects.data?.length && (
          <label className="inline-field">
            Proyecto{' '}
            <select
              value={projectId ?? ''}
              onChange={(e) => go({ projectId: e.target.value || null })}
            >
              <option value="">Todos</option>
              {projects.data.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.key} · {p.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <ArchivedToggle on={archived} onChange={(on) => go({ archived: on })} />
        {since && (
          <RemovableChip
            onRemove={() => go({ since: null })}
            removeLabel="Quitar el filtro de fecha"
          >
            Creadas desde {formatDateTime(since)}
          </RemovableChip>
        )}
        <span className="toolbar-end">
          <ColumnsMenu columns={columns} hidden={hidden} onToggle={toggleColumn} />
        </span>
      </ListToolbar>
      <ErrorMessage error={runs.error} />
      {runs.isPending && <TableSkeleton label="las ejecuciones" columns={6} />}
      {selectedRuns.length > 0 && (
        <BulkRunActions
          runs={selectedRuns}
          archivedView={archived}
          onDone={() => setSelected(new Set())}
        />
      )}
      {runs.data && (
        <RunsTable
          runs={runs.data.items}
          now={now}
          hidden={hidden}
          selection={{
            selected,
            onChange: setSelected,
            rowLabel: (r) =>
              `Seleccionar ${r.workItemKey ?? 'ejecución'} del ${formatDateTime(r.createdAt)}`,
          }}
          empty={
            <EmptyState
              icon={CirclePlay}
              title={
                archived && !(status.length > 0 || !!since || !!projectId || !!q)
                  ? 'No hay ejecuciones archivadas.'
                  : filtered
                    ? 'Ninguna ejecución con estos filtros.'
                    : 'No hay ejecuciones.'
              }
            >
              {filtered && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setParams(new URLSearchParams())}
                >
                  Quitar los filtros
                </Button>
              )}
            </EmptyState>
          }
        />
      )}
      <Pager
        page={page}
        pageSize={PAGE_SIZE}
        total={runs.data?.total ?? 0}
        noun="ejecuciones"
        onPage={(n) => go({ page: n })}
      />
    </>
  )
}
