import { useQuery } from '@tanstack/react-query'
import { Workflow } from 'lucide-react'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { CardsSkeleton } from '../components/ui/Skeleton'
import { formatDateTime } from '../format'

/** Definiciones de workflow, en lectura. En la Fase 1 solo existe `adhoc` (un agente). */
export function WorkflowsPage() {
  const definitions = useQuery({
    queryKey: ['workflow-definitions'],
    queryFn: api.workflowDefinitions,
  })
  return (
    <>
      <h1>Workflows</h1>
      <p className="muted">
        Por ahora todas las ejecuciones usan el workflow implícito <code>adhoc</code>: una fase con
        un agente. Las definiciones propias llegan en la Fase 2.
      </p>
      <ErrorMessage error={definitions.error} />
      {definitions.isPending && <CardsSkeleton label="los workflows" count={1} />}
      {definitions.data?.length === 0 && (
        <EmptyState icon={Workflow} title="Todavía no hay workflows.">
          <p>
            Las ejecuciones usan el workflow <code>adhoc</code> hasta que crees el primero.
          </p>
        </EmptyState>
      )}
      <div className="repo-grid">
        {definitions.data?.map((d) => (
          <article key={d.id} className="card repo-card" aria-labelledby={`wf-${d.id}`}>
            <header className="repo-head">
              <span className="repo-icon" aria-hidden="true">
                <Workflow size={22} strokeWidth={2.25} />
              </span>
              <div className="repo-title">
                <h2 id={`wf-${d.id}`}>{d.key}</h2>
                <span className="muted small">{formatDateTime(d.createdAt)}</span>
              </div>
              <span className="tag">v{d.version}</span>
            </header>
            <details className="repo-section">
              <summary>Definición</summary>
              <pre className="json" tabIndex={0}>
                {d.sourceYaml}
              </pre>
            </details>
          </article>
        ))}
      </div>
    </>
  )
}
