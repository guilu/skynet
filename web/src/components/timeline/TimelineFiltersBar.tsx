import {
  KIND_LABELS,
  ORIGIN_LABELS,
  SEVERITY_LABELS,
  type EventKind,
  type Origin,
  type Severity,
  type TimelineFilters,
} from './timeline'

interface Option {
  value: string
  label: string
}

/** Filtros del timeline: fase, agente, tipo, severidad y origen. */
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
  const active = Object.values(filters).some(Boolean)
  return (
    <div className="timeline-filters" role="group" aria-label="Filtros del timeline">
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
      {active && (
        <button type="button" className="link small" onClick={() => onChange({})}>
          Quitar filtros
        </button>
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
