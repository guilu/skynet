import { useQuery } from '@tanstack/react-query'
import { Server } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { api } from '../api'
import { ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { FilterChips, ListToolbar, type ChipOption } from '../components/list/Toolbar'
import { RunnerRow } from '../components/runners/RunnerRow'
import { CardsSkeleton } from '../components/ui/Skeleton'

const STATUSES: ChipOption[] = [
  { value: 'ONLINE', label: 'En línea', tone: 'ok' },
  { value: 'STALE', label: 'Sin latido', tone: 'warn' },
]

/**
 * Runners registrados, su carga y si siguen enviando latidos; filtrables por estado y por
 * olvidados (en la URL). Olvidar un runner que ya no existe lo quita de aquí y del dashboard.
 */
export function RunnersPage() {
  const [params, setParams] = useSearchParams()
  const status = params.getAll('status')
  const archived = params.get('archived') === 'true'
  const runners = useQuery({
    queryKey: archived ? ['runners', 'archived'] : ['runners'],
    queryFn: () => api.runners(archived ? 'true' : undefined),
    refetchInterval: 15_000,
  })
  const setFilters = (next: { status?: string[]; archived?: boolean }) => {
    const p = new URLSearchParams()
    ;(next.status ?? status).forEach((v) => p.append('status', v))
    if (next.archived ?? archived) p.set('archived', 'true')
    setParams(p, { replace: true })
  }
  const rows = runners.data?.filter((r) => status.length === 0 || status.includes(r.status)) ?? []
  return (
    <>
      <h1>Runners</h1>
      <ErrorMessage error={runners.error} />
      {runners.isPending && <CardsSkeleton label="los runners" />}
      {runners.data?.length === 0 && !archived && (
        <EmptyState companion="offline" title="No hay runners registrados.">
          <p>Arranca uno en la máquina de los repositorios (README, «Runner local»).</p>
        </EmptyState>
      )}
      {(!!runners.data?.length || archived) && (
        <>
          <ListToolbar>
            <FilterChips
              label="Estado"
              options={STATUSES}
              selected={status}
              onChange={(s) => setFilters({ status: s })}
            />
            <ArchivedToggle
              label="Olvidados"
              on={archived}
              onChange={(on) => setFilters({ archived: on })}
            />
          </ListToolbar>
          {rows.length > 0 ? (
            <ul className="runner-rows" aria-label="Runners">
              {rows.map((r) => (
                <li key={r.id}>
                  <RunnerRow runner={r} />
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState
              icon={Server}
              title={
                archived && status.length === 0
                  ? 'No hay runners olvidados.'
                  : 'Ningún runner con este estado.'
              }
            />
          )}
        </>
      )}
    </>
  )
}
