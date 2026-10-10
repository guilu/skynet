import { useQuery } from '@tanstack/react-query'
import { Plus, Workflow } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api, type WorkflowSummary } from '../api'
import { ArchiveMenu, ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { ListToolbar, SearchField } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { TableSkeleton } from '../components/ui/Skeleton'
import { VersionPill } from '../components/workflow/status'
import { formatDateTime } from '../format'

/** Workflow implícito de la Fase 1, que no se archiva. */
export const ADHOC = 'adhoc'

const COLUMNS: Column<WorkflowSummary>[] = [
  {
    id: 'workflow',
    header: 'Workflow',
    hideable: false,
    cell: (w) => (
      <Link to={`/workflows/${encodeURIComponent(w.key)}`} className="row-link">
        <span className="key">{w.key}</span> {w.name ?? ''}
      </Link>
    ),
  },
  { id: 'description', header: 'Descripción', cell: (w) => w.description ?? '—' },
  {
    id: 'published',
    header: 'Publicada',
    cell: (w) =>
      w.published ? (
        <VersionPill version={w.published} />
      ) : (
        <span className="muted">Sin publicar</span>
      ),
  },
  {
    id: 'draft',
    header: 'Borrador',
    cell: (w) => (w.draft ? <VersionPill version={w.draft} /> : '—'),
  },
  {
    id: 'updated',
    header: 'Actualizado',
    cell: (w) => formatDateTime((w.draft ?? w.published)?.updatedAt ?? w.createdAt),
  },
  {
    id: 'actions',
    header: 'Acciones',
    hiddenHeader: true,
    hideable: false,
    cell: (w) =>
      w.key === ADHOC ? null : (
        <ArchiveMenu
          size="sm"
          target={{ kind: 'workflow', id: w.key }}
          name={w.name ?? w.key}
          archivedAt={w.archivedAt}
        />
      ),
  },
]

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
        <DataTable
          label="Workflows"
          columns={COLUMNS}
          rows={workflows.data.filter((w) => !q || matches(w, q))}
          rowKey={(w) => w.id}
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
