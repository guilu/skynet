import { Link } from 'react-router'
import type { Run } from '../api'
import { elapsed, formatCost, formatDateTime, formatDuration, formatNumber } from '../format'
import { Companion } from './companion/Companion'
import { runExpression } from './companion/state'
import type { Column } from './list/DataTable'
import { headlineAgent } from './run/headlineAgent'
import { StatusBadge } from './StatusBadge'

/** Columnas de una lista de ejecuciones: trabajo, estado, agente, inicio, duración, tokens y coste. */
export function runColumns(now: number): Column<Run>[] {
  return [
    {
      id: 'work',
      header: 'Trabajo',
      hideable: false,
      cell: (run) => (
        <Link to={`/runs/${run.id}`} className="row-link">
          <span className="key">{run.workItemKey ?? run.id.slice(0, 8)}</span> {run.workItemTitle}
        </Link>
      ),
    },
    {
      id: 'status',
      header: 'Estado',
      cell: (run) => (
        <span className="status-face">
          <Companion state={runExpression(run, run.stages, now)} size={30} still />
          <StatusBadge status={run.status} />
        </span>
      ),
    },
    {
      id: 'agent',
      header: 'Agente',
      cell: (run) => {
        const agent = headlineAgent(run)
        return (
          <>
            {agent ? <StatusBadge status={agent.status} /> : '—'}
            {agent?.currentTool && <span className="muted small"> {agent.currentTool}</span>}
          </>
        )
      },
    },
    { id: 'created', header: 'Inicio', cell: (run) => formatDateTime(run.createdAt) },
    {
      id: 'duration',
      header: 'Duración',
      cell: (run) => formatDuration(elapsed(run.startedAt ?? run.createdAt, run.finishedAt, now)),
    },
    {
      id: 'tokens',
      header: 'Tokens de salida',
      numeric: true,
      cell: (run) => formatNumber(run.totals.outputTokens),
    },
    {
      id: 'cost',
      header: 'Coste',
      numeric: true,
      cell: (run) => formatCost(run.totals.costUsd),
    },
  ]
}
