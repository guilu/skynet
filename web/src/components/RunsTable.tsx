import { Link } from 'react-router'
import type { Run } from '../api'
import { elapsed, formatCost, formatDateTime, formatDuration, formatNumber } from '../format'
import { headlineAgent } from './run/headlineAgent'
import { StatusBadge } from './StatusBadge'

/** Tabla de ejecuciones con su estado, agente actual, duración, tokens y coste. */
export function RunsTable({ runs, now }: { runs: Run[]; now: number }) {
  if (runs.length === 0) return <p className="muted">No hay ejecuciones.</p>
  return (
    <div className="table-scroll">
      <table className="table">
        <thead>
          <tr>
            <th scope="col">Trabajo</th>
            <th scope="col">Estado</th>
            <th scope="col">Agente</th>
            <th scope="col">Inicio</th>
            <th scope="col">Duración</th>
            <th scope="col" className="num">
              Tokens de salida
            </th>
            <th scope="col" className="num">
              Coste
            </th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => {
            const agent = headlineAgent(run)
            return (
              <tr key={run.id}>
                <td>
                  <Link to={`/runs/${run.id}`}>
                    <strong>{run.workItemKey ?? run.id.slice(0, 8)}</strong> {run.workItemTitle}
                  </Link>
                </td>
                <td>
                  <StatusBadge status={run.status} />
                </td>
                <td>
                  {agent ? <StatusBadge status={agent.status} /> : '—'}
                  {agent?.currentTool && <span className="muted small"> {agent.currentTool}</span>}
                </td>
                <td>{formatDateTime(run.createdAt)}</td>
                <td>
                  {formatDuration(elapsed(run.startedAt ?? run.createdAt, run.finishedAt, now))}
                </td>
                <td className="num">{formatNumber(run.totals.outputTokens)}</td>
                <td className="num">{formatCost(run.totals.costUsd)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
