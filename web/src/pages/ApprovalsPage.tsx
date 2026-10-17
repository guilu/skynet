import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Hand } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { api, type ApprovalRisk, type ApprovalStatus, type ApprovalSummary } from '../api'
import {
  APPROVAL_STATUS_LABEL,
  APPROVAL_STATUS_TONE,
  RISK_LABEL,
  RISK_TONE,
  approvalGate,
  approvalWork,
} from '../components/approval/approvalFormat'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { FilterChips, ListToolbar, type ChipOption } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import { TableSkeleton } from '../components/ui/Skeleton'
import { formatDateTime } from '../format'

const STATUSES: (ChipOption & { value: ApprovalStatus })[] = [
  { value: 'PENDING', label: 'Pendientes', tone: 'warn' },
  { value: 'APPROVED', label: 'Aprobadas', tone: 'ok' },
  { value: 'REJECTED', label: 'Rechazadas', tone: 'bad' },
  { value: 'CHANGES_REQUESTED', label: 'Cambios pedidos', tone: 'active' },
  { value: 'CANCELLED', label: 'Canceladas', tone: 'neutral' },
  { value: 'EXPIRED', label: 'Caducadas', tone: 'bad' },
]

const RISKS: ApprovalRisk[] = ['HIGH', 'MEDIUM', 'LOW']

const COLUMNS: Column<ApprovalSummary>[] = [
  {
    id: 'action',
    header: 'Acción',
    cell: (a) => (
      <Link to={`/approvals/${a.id}`} className="row-link">
        {a.action}
      </Link>
    ),
  },
  {
    id: 'risk',
    header: 'Riesgo',
    cell: (a) => <Pill tone={RISK_TONE[a.risk]}>{RISK_LABEL[a.risk]}</Pill>,
  },
  {
    id: 'status',
    header: 'Estado',
    cell: (a) => (
      <Pill tone={APPROVAL_STATUS_TONE[a.status]}>{APPROVAL_STATUS_LABEL[a.status]}</Pill>
    ),
  },
  { id: 'work', header: 'Trabajo', cell: (a) => approvalWork(a) },
  { id: 'gate', header: 'Puerta', cell: (a) => approvalGate(a) },
  {
    id: 'when',
    header: 'Cuándo',
    cell: (a) =>
      a.decidedAt
        ? `Decidida el ${formatDateTime(a.decidedAt)}`
        : `Pedida el ${formatDateTime(a.createdAt)}`,
  },
]

/**
 * Centro de aprobaciones (W5-C): las peticiones de las puertas, primero las pendientes (las de más
 * riesgo y las más antiguas) y después el historial. Estado, riesgo y proyecto van en la URL.
 */
export function ApprovalsPage() {
  const [params, setParams] = useSearchParams()
  const status = params.getAll('status') as ApprovalStatus[]
  const risk = (params.get('risk') ?? undefined) as ApprovalRisk | undefined
  const projectId = params.get('projectId') ?? undefined
  const approvals = useQuery({
    queryKey: ['approvals', 'list', status.join(','), risk, projectId],
    queryFn: () => api.approvals({ status, risk, projectId }),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
  })
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.projects() })

  const go = (
    next: { status?: ApprovalStatus[]; risk?: string | null; projectId?: string | null } = {},
  ) => {
    const p = new URLSearchParams()
    for (const s of next.status ?? status) p.append('status', s)
    const r = next.risk === undefined ? risk : next.risk
    if (r) p.set('risk', r)
    const project = next.projectId === undefined ? projectId : next.projectId
    if (project) p.set('projectId', project)
    setParams(p)
  }
  const filtered = status.length > 0 || !!risk || !!projectId
  const pending = approvals.data?.filter((a) => a.status === 'PENDING').length ?? 0

  return (
    <>
      <h1>Aprobaciones</h1>
      {approvals.data && !filtered && (
        <p className="muted">
          {pending === 0
            ? 'No hay nada esperando tu decisión.'
            : `${pending} ${pending === 1 ? 'puerta espera' : 'puertas esperan'} tu decisión.`}
        </p>
      )}
      <ListToolbar>
        <FilterChips
          label="Estado"
          options={STATUSES}
          selected={status}
          onChange={(s) => go({ status: s as ApprovalStatus[] })}
        />
        <label className="inline-field">
          Riesgo{' '}
          <select value={risk ?? ''} onChange={(e) => go({ risk: e.target.value || null })}>
            <option value="">Todos</option>
            {RISKS.map((r) => (
              <option key={r} value={r}>
                {RISK_LABEL[r]}
              </option>
            ))}
          </select>
        </label>
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
      </ListToolbar>
      <ErrorMessage error={approvals.error} />
      {approvals.isPending && <TableSkeleton label="las aprobaciones" columns={6} />}
      {approvals.data && (
        <DataTable
          label="Aprobaciones"
          columns={COLUMNS}
          rows={approvals.data}
          rowKey={(a) => a.id}
          rowClassName={(a) => (a.status === 'PENDING' ? 'row-pending' : undefined)}
          empty={
            <EmptyState
              icon={Hand}
              title={
                filtered
                  ? 'Ninguna aprobación con estos filtros.'
                  : 'Aún no hay aprobaciones. Aparecen cuando una ejecución llega a una puerta `human-approval`.'
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
    </>
  )
}
