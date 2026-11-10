import { useQuery } from '@tanstack/react-query'
import { Plus, Workflow } from 'lucide-react'
import { useMemo, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api, type WorkflowSummary } from '../api'
import { ArchiveMenu, ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { ListToolbar, SearchField } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { TableSkeleton } from '../components/ui/Skeleton'
import { VersionPill } from '../components/workflow/status'
import { formatDateTime } from '../format'
import { cn } from '../lib/cn'

/** Workflow implícito de la Fase 1, que no se archiva. */
export const ADHOC = 'adhoc'

/**
 * Tarjeta de un workflow: clave y nombre (enlace a su página), descripción, versión publicada y
 * borrador, y al pie los trabajos pendientes que lo usan (FS-F) y la última actualización.
 */
function WorkflowCard({ workflow: w, pending }: { workflow: WorkflowSummary; pending: number }) {
  return (
    <li className={cn('wf-card', w.archivedAt && 'row-archived')}>
      <div className="wf-card-head">
        <Link to={`/workflows/${encodeURIComponent(w.key)}`} className="wf-card-link">
          <span className="key">{w.key}</span>
          {w.name && <span className="wf-card-name">{w.name}</span>}
        </Link>
        {w.key !== ADHOC && (
          <ArchiveMenu
            size="sm"
            target={{ kind: 'workflow', id: w.key }}
            name={w.name ?? w.key}
            archivedAt={w.archivedAt}
          />
        )}
      </div>
      <p className="wf-card-desc">{w.description ?? 'Sin descripción.'}</p>
      <div className="wf-card-versions">
        {w.published ? (
          <VersionPill version={w.published} />
        ) : (
          <span className="muted small">Sin publicar</span>
        )}
        {w.draft && <VersionPill version={w.draft} />}
      </div>
      <dl className="wf-card-meta">
        <div>
          <dt>Trabajos</dt>
          <dd>{pending > 0 ? `${pending} ${pending === 1 ? 'pendiente' : 'pendientes'}` : '—'}</dd>
        </div>
        <div>
          <dt>Actualizado</dt>
          <dd>{formatDateTime((w.draft ?? w.published)?.updatedAt ?? w.createdAt)}</dd>
        </div>
      </dl>
    </li>
  )
}

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
  const setFilters = (next: { q?: string; archived?: boolean }) => {
    const p = new URLSearchParams()
    const text = next.q ?? q
    if (text) p.set('q', text)
    if (next.archived ?? archived) p.set('archived', 'true')
    setParams(p, { replace: true })
  }

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
        <div className="page-actions">
          <Button onClick={() => void navigate('/workflows/new')}>
            <Plus size={18} strokeWidth={2.75} aria-hidden="true" />
            Nuevo workflow
          </Button>
        </div>
      </div>
      <ListToolbar>
        <SearchField
          label="Buscar workflows"
          placeholder="Buscar workflow"
          value={q}
          onChange={(text) => setFilters({ q: text })}
        />
        <ArchivedToggle on={archived} onChange={(on) => setFilters({ archived: on })} />
      </ListToolbar>
      <ErrorMessage error={workflows.error} />
      {workflows.isPending && <TableSkeleton label="los workflows" columns={4} />}
      {workflows.data && (
        <WorkflowList
          rows={workflows.data.filter((w) => !q || matches(w, q))}
          inUse={inUse}
          empty={
            <EmptyState
              icon={Workflow}
              title={
                q
                  ? 'Ningún workflow coincide con la búsqueda.'
                  : archived
                    ? 'No hay workflows archivados.'
                    : 'Aún no hay workflows.'
              }
            >
              {!q && !archived && <p>Crea el primero con «Nuevo workflow».</p>}
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
