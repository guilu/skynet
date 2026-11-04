import { useQuery } from '@tanstack/react-query'
import { GitPullRequest } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { api, type ChangeView } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { FilterChips, ListToolbar, type ChipOption } from '../components/list/Toolbar'
import { Pill } from '../components/ui/Pill'
import { TableSkeleton } from '../components/ui/Skeleton'
import { CHANGE_STATUS } from '../components/work/phase'
import { formatDateTime } from '../format'

const STATUSES: ChipOption[] = [
  { value: 'OPEN', label: 'Abiertas', tone: 'active' },
  { value: 'MERGED', label: 'Fusionadas', tone: 'ok' },
  { value: 'CLOSED', label: 'Cerradas', tone: 'neutral' },
]

const COLUMNS: Column<ChangeView>[] = [
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
  {
    id: 'where',
    header: 'Proyecto › repositorio',
    cell: (c) => (
      <>
        <Link to={`/projects/${c.projectId}`}>{c.projectName}</Link>
        <span className="muted"> › {c.repositoryName}</span>
      </>
    ),
  },
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

/**
 * Fábrica › Resultados (FS-F): las PRs que han producido los agentes en todos los proyectos, con
 * su trabajo y el intento que las abrió. Se filtran por estado, en la URL (`?status=`).
 */
export function ResultsPage() {
  const [params, setParams] = useSearchParams()
  const status = params.getAll('status')
  const results = useQuery({ queryKey: ['results'], queryFn: api.results, refetchInterval: 30_000 })
  const setStatus = (next: string[]) => {
    const p = new URLSearchParams()
    next.forEach((s) => p.append('status', s))
    setParams(p, { replace: true })
  }
  const all = results.data ?? []
  const rows = all.filter((c) => status.length === 0 || status.includes(c.status))
  const merged = all.filter((c) => c.status === 'MERGED').length
  const open = all.filter((c) => c.status === 'OPEN').length
  return (
    <>
      <h1>Resultados</h1>
      {results.data && (
        <p className="muted">
          {open} {open === 1 ? 'PR abierta' : 'PRs abiertas'} · {merged}{' '}
          {merged === 1 ? 'fusionada' : 'fusionadas'}
        </p>
      )}
      <ErrorMessage error={results.error} />
      {results.isPending && <TableSkeleton label="los resultados" />}
      {results.data && (
        <>
          {all.length > 0 && (
            <ListToolbar>
              <FilterChips
                label="Estado"
                options={STATUSES}
                selected={status}
                onChange={setStatus}
              />
            </ListToolbar>
          )}
          <DataTable
            label="Resultados"
            columns={COLUMNS}
            rows={rows}
            rowKey={(c) => c.id}
            empty={
              <EmptyState icon={GitPullRequest} title="Aún no hay resultados.">
                <p>Cada PR que abra un agente saldrá aquí, con su trabajo y su intento.</p>
              </EmptyState>
            }
          />
        </>
      )}
    </>
  )
}
