import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Sparkles, X } from 'lucide-react'
import { Link } from 'react-router'
import { api, type MemoryCurationReport, type MemoryProposal } from '../../api'
import { formatDateTime } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { EmptyState } from '../list/DataTable'
import { LongText } from '../run/LongText'
import { Button } from '../ui/Button'
import { CardsSkeleton } from '../ui/Skeleton'

const STATUS_TEXT: Record<Exclude<MemoryProposal['status'], 'PENDING'>, string> = {
  ACCEPTED: 'Aceptada',
  REJECTED: 'Rechazada',
}

/**
 * Los informes del curador de la memoria (MV-E), el más reciente primero: su resumen, las páginas
 * que escribió, lo que no valía y sus propuestas de archivar o fusionar entradas, que acepta o
 * rechaza una persona. Aceptar archiva las entradas; rechazar no cambia nada.
 */
export function MemoryCurations({
  projectId,
  editable,
  onEntry,
}: {
  projectId: string
  editable: boolean
  onEntry: (id: string) => void
}) {
  const curations = useQuery({
    queryKey: ['memory-curations', projectId],
    queryFn: () => api.memoryCurations(projectId),
  })

  if (curations.error) return <ErrorMessage error={curations.error} />
  if (!curations.data) return <CardsSkeleton label="los informes del curador" />
  if (curations.data.length === 0) {
    return (
      <EmptyState icon={Sparkles} title="El curador todavía no ha repasado la memoria.">
        <p>
          Lanza el workflow «Curar la memoria» sobre un trabajo del proyecto, a mano o con un
          disparador programado. Escribe páginas de la wiki y propone archivar o fusionar entradas.
        </p>
      </EmptyState>
    )
  }
  return (
    <ol className="memory-timeline" aria-label="Informes del curador">
      {curations.data.map((c) => (
        <li key={c.id}>
          <CurationReport curation={c} editable={editable} onEntry={onEntry} />
        </li>
      ))}
    </ol>
  )
}

function CurationReport({
  curation: c,
  editable,
  onEntry,
}: {
  curation: MemoryCurationReport
  editable: boolean
  onEntry: (id: string) => void
}) {
  const pending = c.proposals.filter((p) => p.status === 'PENDING').length
  return (
    <article className="curation" aria-label={`Curación del ${formatDateTime(c.createdAt)}`}>
      <h3 className="curation-head">
        Curación del {formatDateTime(c.createdAt)}
        {pending > 0 && (
          <span className="tag tag-warn">
            {pending === 1 ? '1 propuesta pendiente' : `${pending} propuestas pendientes`}
          </span>
        )}
      </h3>
      <LongText text={c.summary} />
      <p className="small">
        {c.workflowRunId && <Link to={`/runs/${c.workflowRunId}`}>Ver la ejecución</Link>}
        {c.workflowRunId && c.agentRunId && ' · '}
        {c.agentRunId && <Link to={`/agent-runs/${c.agentRunId}`}>Ver la invocación</Link>}
      </p>

      {c.pages.length > 0 && (
        <>
          <h4>Páginas que escribió</h4>
          <ul className="memory-readers small">
            {c.pages.map((p) => (
              <li key={`${p.scope}-${p.slug}`}>
                <strong>{p.title}</strong> <code>{p.slug}</code>{' '}
                <span className="muted">
                  · {p.scope === 'project' ? 'todo el proyecto' : 'su repositorio'} · v{p.version}
                  {!p.changed && ' · sin cambios'}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}

      {c.proposals.length > 0 && (
        <>
          <h4>Propuestas</h4>
          <ul className="memory-list">
            {c.proposals.map((p) => (
              <li key={p.id}>
                <ProposalCard
                  proposal={p}
                  projectId={c.projectId}
                  editable={editable}
                  onEntry={onEntry}
                />
              </li>
            ))}
          </ul>
        </>
      )}

      {c.rejected.length > 0 && (
        <details className="small">
          <summary>Lo que no se aplicó ({c.rejected.length})</summary>
          <ul className="memory-readers">
            {c.rejected.map((r, i) => (
              <li key={`${i}-${r.item}`}>
                <code>{r.item}</code> <span className="muted">· {r.reason}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </article>
  )
}

function ProposalCard({
  proposal: p,
  projectId,
  editable,
  onEntry,
}: {
  proposal: MemoryProposal
  projectId: string
  editable: boolean
  onEntry: (id: string) => void
}) {
  const queryClient = useQueryClient()
  const decide = useMutation({
    mutationFn: (decision: 'accept' | 'reject') => api.decideProposal(p.id, decision),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['memory-curations', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['memory', projectId] }),
        ...[...p.observationIds, p.intoId]
          .filter((id): id is string => id != null)
          .map((id) => queryClient.invalidateQueries({ queryKey: ['memory-entry', id] })),
      ]),
  })
  const merge = p.action === 'MERGE'
  return (
    <section className="memory-item" aria-label={merge ? 'Fusionar entradas' : 'Archivar entradas'}>
      <span className="memory-item-head">
        <span className="tag tag-on">{merge ? 'Fusionar' : 'Archivar'}</span>
        <strong>{p.reason}</strong>
      </span>
      {merge && p.intoId && (
        <p className="small">
          Se queda: <EntryLink id={p.intoId} onEntry={onEntry} />
        </p>
      )}
      <p className="small">
        Se archiva{p.observationIds.length > 1 ? 'n' : ''}:{' '}
        {p.observationIds.map((id, i) => (
          <span key={id}>
            {i > 0 && ', '}
            <EntryLink id={id} onEntry={onEntry} />
          </span>
        ))}
      </p>
      {p.status === 'PENDING' ? (
        editable && (
          <div className="memory-actions">
            <Button size="sm" disabled={decide.isPending} onClick={() => decide.mutate('accept')}>
              <Check size={16} strokeWidth={2.5} aria-hidden="true" />
              Aceptar
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={decide.isPending}
              onClick={() => decide.mutate('reject')}
            >
              <X size={16} strokeWidth={2.5} aria-hidden="true" />
              Rechazar
            </Button>
          </div>
        )
      ) : (
        <small className="muted">
          {STATUS_TEXT[p.status]}
          {p.decidedBy && ` por ${p.decidedBy}`}
          {p.decidedAt && `, ${formatDateTime(p.decidedAt)}`}
        </small>
      )}
      <ErrorMessage error={decide.error} />
    </section>
  )
}

/** Una entrada por su título, que se abre en el panel de la memoria. */
function EntryLink({ id, onEntry }: { id: string; onEntry: (id: string) => void }) {
  const entry = useQuery({
    queryKey: ['memory-entry', id],
    queryFn: () => api.memoryEntry(id),
    retry: false,
  })
  if (entry.error) return <span className="muted">una entrada que ya no está</span>
  const o = entry.data
  return (
    <Button variant="link" onClick={() => onEntry(id)}>
      {o ? o.title : 'cargando…'}
      {o?.archivedAt && ' (archivada)'}
    </Button>
  )
}
