import { useQuery } from '@tanstack/react-query'
import { Plug } from 'lucide-react'
import { Link } from 'react-router'
import { api, type ProviderKind, type Runner } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { QuotaSettingsSection } from '../components/settings/QuotaSettings'
import { Pill } from '../components/ui/Pill'
import { SimulatedPill } from '../components/ui/SimulatedPill'
import { TableSkeleton } from '../components/ui/Skeleton'

const CAPABILITY: Record<string, string> = {
  RESUME: 'reanudar',
  FORK: 'bifurcar',
  STRUCTURED_OUTPUT: 'salida estructurada',
  MAX_TURNS: 'límite de turnos',
  MEMORY_TOOLS: 'memoria',
}

interface ProviderRow {
  kind: ProviderKind
  /** Los runners que lo tienen, con la versión de su CLI y si es simulado. */
  installs: { runner: Runner; version: string | null; simulated: boolean }[]
}

const COLUMNS: Column<ProviderRow>[] = [
  {
    id: 'name',
    header: 'Proveedor',
    hideable: false,
    cell: (p) => <strong>{p.kind.displayName}</strong>,
  },
  {
    id: 'status',
    header: 'Disponible',
    cell: (p) =>
      p.kind.onlineRunners > 0 ? (
        <Pill tone="ok">
          {p.kind.onlineRunners} {p.kind.onlineRunners === 1 ? 'runner' : 'runners'} en línea
        </Pill>
      ) : (
        <Pill tone="warn">Sin runner</Pill>
      ),
  },
  {
    id: 'installs',
    header: 'Runners y versión',
    cell: (p) =>
      p.installs.length === 0 ? (
        <span className="muted">—</span>
      ) : (
        <ul className="provider-installs">
          {p.installs.map((i) => (
            <li key={i.runner.id}>
              {i.runner.name}
              <span className="muted"> · {i.version ?? 'versión desconocida'}</span>
              {i.simulated && (
                <>
                  {' '}
                  <SimulatedPill />
                </>
              )}
            </li>
          ))}
        </ul>
      ),
  },
  {
    id: 'capabilities',
    header: 'Admite',
    cell: (p) => (
      <span className="muted small">
        {p.kind.capabilities.map((c) => CAPABILITY[c] ?? c).join(' · ') || '—'}
      </span>
    ),
  },
]

/** Cruza los proveedores que Skynet sabe ejecutar con los runners que los tienen instalados. */
function providerRows(kinds: ProviderKind[], runners: Runner[]): ProviderRow[] {
  return kinds.map((kind) => ({
    kind,
    installs: runners.flatMap((runner) =>
      runner.providers
        .filter((p) => p.id === kind.id)
        .map((p) => ({ runner, version: p.version, simulated: p.simulated })),
    ),
  }))
}

/**
 * Administración › Proveedores (FS-F): con qué CLIs trabajan los agentes, en qué runners están y
 * con qué versión, y qué hacer cuando uno se queda sin cuota (antes en Ajustes › Ejecución).
 */
export function ProvidersPage() {
  const kinds = useQuery({ queryKey: ['providers'], queryFn: api.providers })
  const runners = useQuery({
    queryKey: ['runners'],
    queryFn: () => api.runners(),
    refetchInterval: 15_000,
  })
  return (
    <>
      <h1>Proveedores</h1>
      <p className="muted">
        Los CLIs de agentes que hay en tus runners. Lo que gasta cada uno está en{' '}
        <Link to="/usage">Consumo</Link>.
      </p>
      <ErrorMessage error={kinds.error ?? runners.error} />
      {(kinds.isPending || runners.isPending) && <TableSkeleton label="los proveedores" />}
      {kinds.data && runners.data && (
        <DataTable
          label="Proveedores"
          columns={COLUMNS}
          rows={providerRows(kinds.data, runners.data)}
          rowKey={(p) => p.kind.id}
          empty={
            <EmptyState icon={Plug} title="No hay proveedores.">
              <p>Arranca un runner con algún CLI de agentes instalado.</p>
            </EmptyState>
          }
        />
      )}
      <QuotaSettingsSection />
    </>
  )
}
