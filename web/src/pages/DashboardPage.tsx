import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { RunsTable } from '../components/RunsTable'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'
import { useNow } from '../useNow'

/**
 * Dashboard de excepciones (ADR-0001 §3.2): solo lo que requiere atención, cada bloque enlazado a
 * su lista completa. Las métricas agregadas llegan en M6.
 */
export function DashboardPage() {
  const summary = useQuery({
    queryKey: ['dashboard'],
    queryFn: api.dashboard,
    refetchInterval: 10_000,
  })
  const now = useNow(true)
  const data = summary.data
  const calm =
    data &&
    data.activeRunsTotal === 0 &&
    data.recentFailures.length === 0 &&
    data.unresponsiveAgents.length === 0 &&
    data.staleRunners.length === 0

  return (
    <>
      <h1>Dashboard</h1>
      <ErrorMessage error={summary.error} />
      {calm && <p className="muted">Nada requiere atención ahora mismo.</p>}
      {data && data.unresponsiveAgents.length > 0 && (
        <section className="card attention">
          <h2>Agentes sin actividad ({data.unresponsiveAgents.length})</h2>
          <ul className="list">
            {data.unresponsiveAgents.map((a) => (
              <li key={a.agentRunId}>
                <StatusBadge status="UNRESPONSIVE" />{' '}
                <Link to={`/runs/${a.workflowRunId}?agent=${a.agentRunId}`}>
                  {a.workItemKey ?? a.workflowRunId}
                </Link>{' '}
                <span className="muted small">
                  última actividad {formatDateTime(a.lastActivityAt)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {data && data.staleRunners.length > 0 && (
        <section className="card attention">
          <h2>Runners sin latido ({data.staleRunners.length})</h2>
          <ul className="list">
            {data.staleRunners.map((r) => (
              <li key={r.id}>
                <StatusBadge status={r.status} /> {r.name}{' '}
                <span className="muted small">
                  último latido {formatDateTime(r.lastHeartbeatAt)}
                </span>
              </li>
            ))}
          </ul>
          <Link to="/runners">Ver runners</Link>
        </section>
      )}
      {data && data.activeRunsTotal > 0 && (
        <section>
          <h2>Ejecuciones activas ({data.activeRunsTotal})</h2>
          <RunsTable runs={data.activeRuns} now={now} />
          <Link to="/runs?status=PENDING&status=RUNNING">Ver todas las activas</Link>
        </section>
      )}
      {data && data.recentFailures.length > 0 && (
        <section>
          <h2>Fallidas en las últimas 24 horas ({data.recentFailures.length})</h2>
          <RunsTable runs={data.recentFailures} now={now} />
          <Link to="/runs?status=FAILED">Ver todas las fallidas</Link>
        </section>
      )}
    </>
  )
}
