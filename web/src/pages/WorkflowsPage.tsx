import { useQuery } from '@tanstack/react-query'
import { Plus, Workflow } from 'lucide-react'
import { useMemo, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate, useSearchParams } from 'react-router'
import { api, type WorkflowSummary } from '../api'
import { ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { FilterChips, ListToolbar, SearchField } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { TableSkeleton } from '../components/ui/Skeleton'
import { providerInfo } from '../components/workflow/overview'
import { WorkflowCard } from '../components/workflow/WorkflowCard'

/** Filtros de la lista: con borrador, entregan por PR y solo leen. */
const FILTERS = [
  { value: 'draft', label: 'Con borrador', test: (w: WorkflowSummary) => w.draft != null },
  { value: 'pr', label: 'Entregan por PR', test: (w: WorkflowSummary) => !!w.overview?.delivers },
  { value: 'readonly', label: 'Solo leen', test: (w: WorkflowSummary) => !!w.overview?.readOnly },
] as const

/** Proveedores que aparecen en alguno de los workflows, por orden de aparición. */
const providersOf = (rows: WorkflowSummary[]) => [
  ...new Set(rows.flatMap((w) => w.overview?.providers ?? [])),
]

const listParam = (value: string | null) => (value ? value.split(',').filter(Boolean) : [])

const matches = (w: WorkflowSummary, text: string) =>
  `${w.key} ${w.name ?? ''} ${w.description ?? ''}`.toLowerCase().includes(text.toLowerCase())

/**
 * Workflows con su versión publicada y su borrador, búsqueda, el chip «Archivados» y el alta de uno
 * nuevo (en su propia página, con el editor).
 */
export function WorkflowsPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const archived = params.get('archived') === 'true'
  const filters = listParam(params.get('f'))
  const providers = listParam(params.get('prov'))
  const workflows = useQuery({
    queryKey: archived ? ['workflows', 'archived'] : ['workflows'],
    queryFn: () => api.workflows(archived ? 'true' : undefined),
  })
  const pending = useQuery({
    queryKey: ['work-items', 'pending'],
    queryFn: () => api.pendingWorkItems(),
  })
  const inUse = useMemo(() => {
    const counts = new Map<string, number>()
    for (const w of pending.data ?? [])
      if (w.workflowKey) counts.set(w.workflowKey, (counts.get(w.workflowKey) ?? 0) + 1)
    return counts
  }, [pending.data])
  const setFilters = (next: {
    q?: string
    archived?: boolean
    filters?: string[]
    providers?: string[]
  }) => {
    const p = new URLSearchParams()
    const text = next.q ?? q
    if (text) p.set('q', text)
    const f = next.filters ?? filters
    if (f.length > 0) p.set('f', f.join(','))
    const prov = next.providers ?? providers
    if (prov.length > 0) p.set('prov', prov.join(','))
    if (next.archived ?? archived) p.set('archived', 'true')
    setParams(p, { replace: true })
  }
  const all = workflows.data ?? []
  const rows = all.filter(
    (w) =>
      (!q || matches(w, q)) &&
      FILTERS.every((f) => !filters.includes(f.value) || f.test(w)) &&
      (providers.length === 0 || providers.some((p) => w.overview?.providers.includes(p))),
  )
  const filtering = !!q || filters.length > 0 || providers.length > 0

  return (
    <>
      <div className="page-head">
        <div className="page-title">
          <h1>Workflows</h1>
          <p className="muted small">
            Fases y agentes en YAML. Se publican como versiones que ya no cambian; las ejecuciones
            usan la versión con la que arrancaron.
          </p>
        </div>
        <div className="page-actions wf-new-desk">
          <Button onClick={() => void navigate('/workflows/new')}>
            <Plus size={18} strokeWidth={2.75} aria-hidden="true" />
            Nuevo workflow
          </Button>
        </div>
      </div>
      {/* En el móvil, «Nuevo» flota sobre la barra de abajo; va al body para que nada lo recorte. */}
      {createPortal(
        <Button
          className="wf-fab"
          aria-label="Nuevo workflow"
          onClick={() => void navigate('/workflows/new')}
        >
          <Plus size={18} strokeWidth={2.75} aria-hidden="true" />
          Nuevo
        </Button>,
        document.body,
      )}
      <ListToolbar>
        <SearchField
          label="Buscar workflows"
          placeholder="Buscar workflow"
          value={q}
          onChange={(text) => setFilters({ q: text })}
        />
        <ArchivedToggle on={archived} onChange={(on) => setFilters({ archived: on })} />
      </ListToolbar>
      {all.length > 0 && (
        <div className="wf-filters">
          <FilterChips
            label="Filtrar workflows"
            options={FILTERS.map((f) => ({
              value: f.value,
              label: `${f.label} ${all.filter(f.test).length}`,
            }))}
            selected={filters}
            onChange={(next) => setFilters({ filters: next })}
          />
          {providersOf(all).filter((p) => p !== 'repository').length > 1 && (
            <FilterChips
              label="Filtrar por proveedor"
              options={providersOf(all)
                .filter((p) => p !== 'repository')
                .map((p) => ({ value: p, label: providerInfo(p).name }))}
              selected={providers}
              onChange={(next) => setFilters({ providers: next })}
            />
          )}
        </div>
      )}
      <ErrorMessage error={workflows.error} />
      {workflows.isPending && <TableSkeleton label="los workflows" columns={4} />}
      {workflows.data && (
        <WorkflowList
          rows={rows}
          inUse={inUse}
          empty={
            <EmptyState
              icon={Workflow}
              title={
                filtering
                  ? 'Ningún workflow coincide con la búsqueda.'
                  : archived
                    ? 'No hay workflows archivados.'
                    : 'Aún no hay workflows.'
              }
            >
              {!filtering && !archived && <p>Crea el primero con «Nuevo workflow».</p>}
            </EmptyState>
          }
        />
      )}
    </>
  )
}

function WorkflowList({
  rows,
  inUse,
  empty,
}: {
  rows: WorkflowSummary[]
  inUse: Map<string, number>
  empty: ReactNode
}) {
  if (rows.length === 0) return <>{empty}</>
  return (
    <ul className="wf-cards" aria-label="Workflows">
      {rows.map((w) => (
        <WorkflowCard key={w.id} workflow={w} pending={inUse.get(w.key) ?? 0} />
      ))}
    </ul>
  )
}
