import {
  KIND_LABELS,
  ORIGIN_LABELS,
  SEVERITY_LABELS,
  type EventKind,
  type Origin,
  type Severity,
  type TimelineFilters,
} from './timeline'
import { ListFilter } from 'lucide-react'
import { useState } from 'react'
import { useMediaQuery } from '../../useMediaQuery'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'

interface Option {
  value: string
  label: string
}

/**
 * Filtros del timeline: fase, agente, tipo, severidad y origen. En el móvil no caben en una fila:
 * un botón «Filtrar» (con cuántos hay puestos) los abre en un panel que sube desde abajo.
 */
export function TimelineFiltersBar({
  filters,
  stages,
  agents,
  onChange,
}: {
  filters: TimelineFilters
  stages: Option[]
  agents: Option[]
  onChange: (filters: TimelineFilters) => void
}) {
  const set = (key: keyof TimelineFilters) => (value: string) =>
    onChange({ ...filters, [key]: value || undefined })
  const count = Object.values(filters).filter(Boolean).length
  const narrow = useMediaQuery('(max-width: 640px)')
  const [open, setOpen] = useState(false)
  const fields = (
    <>
      {stages.length > 1 && (
        <Select label="Fase" value={filters.stage} options={stages} onChange={set('stage')} />
      )}
      {agents.length > 1 && (
        <Select label="Agente" value={filters.agent} options={agents} onChange={set('agent')} />
      )}
      <Select
        label="Tipo"
        value={filters.kind}
        options={entries<EventKind>(KIND_LABELS)}
        onChange={set('kind')}
      />
      <Select
        label="Severidad"
        value={filters.severity}
        options={entries<Severity>(SEVERITY_LABELS).filter((o) => o.value !== 'info')}
        allLabel="Todas"
        onChange={set('severity')}
      />
      <Select
        label="Origen"
        value={filters.origin}
        options={entries<Origin>(ORIGIN_LABELS)}
        onChange={set('origin')}
      />
    </>
  )

  if (narrow) {
    return (
      <div className="timeline-filters">
        <Button
          variant="secondary"
          size="sm"
          onClick={() => setOpen(true)}
          aria-label={
            count === 0 ? 'Filtrar' : `Filtrar: ${count} ${count === 1 ? 'activo' : 'activos'}`
          }
        >
          <ListFilter size={18} strokeWidth={2.5} aria-hidden="true" />
          Filtrar
          {count > 0 && <span className="filter-count">{count}</span>}
        </Button>
        <Sheet open={open} onOpenChange={setOpen} title="Filtrar">
          <div className="filter-sheet" role="group" aria-label="Filtros del timeline">
            {fields}
            <div className="filter-sheet-actions">
              {count > 0 && (
                <Button variant="secondary" onClick={() => onChange({})}>
                  Quitar filtros
                </Button>
              )}
              <Button onClick={() => setOpen(false)}>Ver resultados</Button>
            </div>
          </div>
        </Sheet>
      </div>
    )
  }

  return (
    <div className="timeline-filters" role="group" aria-label="Filtros del timeline">
      {fields}
      {count > 0 && (
        <Button variant="link" className="small" onClick={() => onChange({})}>
          Quitar filtros
        </Button>
      )}
    </div>
  )
}

function Select({
  label,
  value,
  options,
  allLabel = 'Todos',
  onChange,
}: {
  label: string
  value: string | undefined
  options: Option[]
  allLabel?: string
  onChange: (value: string) => void
}) {
  return (
    <label className="inline-field">
      {label}
      <select value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
        <option value="">{allLabel}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

const entries = <K extends string>(labels: Record<K, string>): Option[] =>
  (Object.entries(labels) as [K, string][]).map(([value, label]) => ({ value, label }))
