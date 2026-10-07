import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { api, type RunStatus } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { RunsTable } from '../components/RunsTable'
import { formatDateTime } from '../format'
import { useNow } from '../useNow'

const PAGE_SIZE = 25

/** Filtros de la página; los valores de `status` van a la URL tal cual. */
const FILTERS: { label: string; status: RunStatus[] }[] = [
  { label: 'Todas', status: [] },
  { label: 'Activas', status: ['PENDING', 'RUNNING'] },
  { label: 'Fallidas', status: ['FAILED'] },
  { label: 'Completadas', status: ['SUCCEEDED'] },
  { label: 'Canceladas', status: ['CANCELLED'] },
]

/** Todas las ejecuciones, filtrables por estado. El filtro y la página viven en la URL. */
export function RunsPage() {
  const [params, setParams] = useSearchParams()
  const status = params.getAll('status') as RunStatus[]
  const since = params.get('since') ?? undefined
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const runs = useQuery({
    queryKey: ['runs', 'list', status.join(','), since, page],
    queryFn: () => api.listRuns({ status, since, page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
  })
  const now = useNow(true)
  const selected = FILTERS.findIndex((f) => f.status.join(',') === status.join(','))
  const total = runs.data?.total ?? 0
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const go = (next: { status?: RunStatus[]; since?: string | null; page?: number }) => {
    const p = new URLSearchParams()
    for (const s of next.status ?? status) p.append('status', s)
    const from = next.since === undefined ? since : next.since
    if (from) p.set('since', from)
    if (next.page) p.set('page', String(next.page))
    setParams(p)
  }

  return (
    <>
      <h1>Ejecuciones</h1>
      <label className="inline-field">
        Estado{' '}
        <select
          value={selected < 0 ? '' : selected}
          onChange={(e) => go({ status: FILTERS[Number(e.target.value)].status, page: 0 })}
        >
          {selected < 0 && <option value="">{status.join(', ')}</option>}
          {FILTERS.map((f, i) => (
            <option key={f.label} value={i}>
              {f.label}
            </option>
          ))}
        </select>
      </label>
      {since && (
        <p className="small">
          Creadas desde {formatDateTime(since)}{' '}
          <button type="button" className="link" onClick={() => go({ since: null, page: 0 })}>
            Quitar
          </button>
        </p>
      )}
      <ErrorMessage error={runs.error} />
      {runs.data && <RunsTable runs={runs.data.items} now={now} />}
      {total > PAGE_SIZE && (
        <nav className="pager" aria-label="Paginación">
          <button type="button" disabled={page === 0} onClick={() => go({ page: page - 1 })}>
            Anterior
          </button>
          <span>
            Página {page + 1} de {pages} · {total} ejecuciones
          </span>
          <button type="button" disabled={page + 1 >= pages} onClick={() => go({ page: page + 1 })}>
            Siguiente
          </button>
        </nav>
      )}
    </>
  )
}
