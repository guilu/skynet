import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Server } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { api, type Runner } from '../api'
import { ArchiveMenu, ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { ColumnsMenu, DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { useHiddenColumns } from '../components/list/columns'
import { FilterChips, ListToolbar, type ChipOption } from '../components/list/Toolbar'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'
import { Button } from '../components/ui/Button'
import { TableSkeleton } from '../components/ui/Skeleton'

const STATUSES: ChipOption[] = [
  { value: 'ONLINE', label: 'En línea', tone: 'ok' },
  { value: 'STALE', label: 'Sin latido', tone: 'warn' },
]

const COLUMNS: Column<Runner>[] = [
  { id: 'name', header: 'Nombre', hideable: false, cell: (r) => <strong>{r.name}</strong> },
  { id: 'status', header: 'Estado', cell: (r) => <StatusBadge status={r.status} /> },
  {
    id: 'load',
    header: 'Agentes / capacidad',
    cell: (r) => <Capacity used={r.activeAgents} capacity={r.capacity} />,
  },
  { id: 'heartbeat', header: 'Último latido', cell: (r) => formatDateTime(r.lastHeartbeatAt) },
  {
    id: 'versions',
    header: 'Versiones',
    cell: (r) => (
      <span className="muted small">
        runner {r.runnerVersion ?? '—'} · Claude Code {r.providerVersion ?? '—'}
      </span>
    ),
  },
  {
    id: 'actions',
    header: 'Acciones',
    hiddenHeader: true,
    hideable: false,
    cell: (r) => (
      <span className="row-actions">
        {r.archivedAt == null && <RevokeToken runner={r} />}
        <ArchiveMenu
          size="sm"
          target={{ kind: 'runner', id: r.id }}
          name={r.name}
          archivedAt={r.archivedAt}
        />
      </span>
    ),
  },
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
  const [hidden, toggleColumn] = useHiddenColumns('runners')
  const rows = runners.data?.filter((r) => status.length === 0 || status.includes(r.status)) ?? []
  return (
    <>
      <h1>Runners</h1>
      <ErrorMessage error={runners.error} />
      {runners.isPending && <TableSkeleton label="los runners" columns={5} rows={3} />}
      {runners.data?.length === 0 && !archived && (
        <EmptyState icon={Server} title="No hay runners registrados.">
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
            <span className="toolbar-end">
              <ColumnsMenu columns={COLUMNS} hidden={hidden} onToggle={toggleColumn} />
            </span>
          </ListToolbar>
          <DataTable
            label="Runners"
            columns={COLUMNS}
            rows={rows}
            rowKey={(r) => r.id}
            hidden={hidden}
            empty={
              <EmptyState
                icon={Server}
                title={
                  archived && status.length === 0
                    ? 'No hay runners olvidados.'
                    : 'Ningún runner con este estado.'
                }
              />
            }
          />
        </>
      )}
    </>
  )
}

/** Carga del runner: «1 / 2» y una barra que se llena con los agentes en marcha. */
function Capacity({ used, capacity }: { used: number; capacity: number }) {
  const ratio = capacity > 0 ? Math.min(1, used / capacity) : 0
  return (
    <span className="capacity">
      <span className="num">
        {used} / {capacity}
      </span>
      <span className="capacity-bar" aria-hidden="true">
        <span style={{ width: `${ratio * 100}%` }} data-full={ratio >= 1 || undefined} />
      </span>
    </span>
  )
}

/**
 * Invalida el token del runner. El runner legítimo se vuelve a registrar solo con el secreto de
 * registro; quien solo tenga el token se queda fuera.
 */
function RevokeToken({ runner }: { runner: Runner }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const revoke = useMutation({
    mutationFn: () => api.revokeRunner(runner.id),
    onSuccess: () => {
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: ['runners'] })
    },
  })
  if (revoke.isSuccess && !confirming) {
    return (
      <span role="status" className="muted small">
        Token revocado
      </span>
    )
  }
  if (!confirming) {
    return (
      <Button variant="secondary-danger" size="sm" onClick={() => setConfirming(true)}>
        Revocar token…
      </Button>
    )
  }
  return (
    <span className="small">
      ¿Revocar el token de {runner.name}? Si es tu runner, se volverá a registrar solo.{' '}
      <Button
        variant="danger"
        size="sm"
        onClick={() => revoke.mutate()}
        disabled={revoke.isPending}
      >
        Sí, revocar
      </Button>{' '}
      <Button variant="secondary" size="sm" onClick={() => setConfirming(false)}>
        No
      </Button>
      <ErrorMessage error={revoke.error} />
    </span>
  )
}
