import { CirclePlay } from 'lucide-react'
import type { ReactNode } from 'react'
import type { Run } from '../api'
import { DataTable, EmptyState, type RowSelection } from './list/DataTable'
import { runColumns } from './runColumns'

/** Tabla de ejecuciones con su estado, agente actual, duración, tokens y coste. */
export function RunsTable({
  runs,
  now,
  label = 'Ejecuciones',
  hidden,
  empty,
  selection,
}: {
  runs: Run[]
  now: number
  label?: string
  hidden?: Set<string>
  empty?: ReactNode
  selection?: RowSelection<Run>
}) {
  return (
    <DataTable
      label={label}
      columns={runColumns(now)}
      rows={runs}
      rowKey={(r) => r.id}
      hidden={hidden}
      empty={empty ?? <EmptyState icon={CirclePlay} title="No hay ejecuciones." />}
      selection={selection}
    />
  )
}
