import { useQuery } from '@tanstack/react-query'
import { BotOff, CircleCheck, ServerOff } from 'lucide-react'
import { Link } from 'react-router'
import { api } from '../api'
import { DashboardMetrics } from '../components/DashboardMetrics'
import { ErrorMessage } from '../components/ErrorMessage'
import { RunsTable } from '../components/RunsTable'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'
import { useNow } from '../useNow'
import { TableSkeleton } from '../components/ui/Skeleton'

/**
 * Dashboard (ADR-0001 §3.2): primero lo que requiere atención, cada bloque enlazado a su lista
 * completa, y después las métricas del periodo elegido.
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
      {summary.isPending && <TableSkeleton label="lo que requiere atención" columns={5} rows={3} />}
      {calm && (
        <p className="calm">
          <CircleCheck size={20} aria-hidden="true" />
          Nada requiere atención ahora mismo.
        </p>
      )}
      {data && (data.unresponsiveAgents.length > 0 || data.staleRunners.length > 0) && (
        <div className="attention-grid">
          {data.unresponsiveAgents.length > 0 && (
            <section className="card attention">
              <div className="attention-head">
                <span className="attention-icon" aria-hidden="true">
                  <BotOff size={20} />
                </span>
                <h2>Agentes sin actividad ({data.unresponsiveAgents.length})</h2>
              </div>
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
          {data.staleRunners.length > 0 && (
            <section className="card attention">
              <div className="attention-head">
                <span className="attention-icon" aria-hidden="true">
                  <ServerOff size={20} />
                </span>
                <h2>Runners sin latido ({data.staleRunners.length})</h2>
              </div>
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
        </div>
      )}
      {data && data.activeRunsTotal > 0 && (
        <section>
          <div className="section-head">
            <h2>Ejecuciones activas ({data.activeRunsTotal})</h2>
            <Link to="/runs?status=PENDING&status=RUNNING">Ver todas las activas</Link>
          </div>
          <RunsTable runs={data.activeRuns} now={now} label="Ejecuciones activas" />
        </section>
      )}
      {data && data.recentFailures.length > 0 && (
        <section>
          <div className="section-head">
            <h2>Fallidas en las últimas 24 horas ({data.recentFailures.length})</h2>
            <Link to="/runs?status=FAILED">Ver todas las fallidas</Link>
          </div>
          <RunsTable
            runs={data.recentFailures}
            now={now}
            label="Fallidas en las últimas 24 horas"
          />
        </section>
      )}
      <DashboardMetrics />
    </>
  )
}
