import { useQuery } from '@tanstack/react-query'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
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
      <ul className="list">
        {definitions.data?.map((d) => (
          <li key={d.id}>
            <strong>{d.key}</strong> <span className="muted">v{d.version}</span>{' '}
            <span className="muted small">· {formatDateTime(d.createdAt)}</span>
            <details>
              <summary>Definición</summary>
              <pre className="json">{d.sourceYaml}</pre>
            </details>
          </li>
        ))}
      </ul>
    </>
  )
}
