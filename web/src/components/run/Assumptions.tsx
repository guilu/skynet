import { useQuery } from '@tanstack/react-query'
import { api, type Assumption } from '../../api'
import { assumptionsKey } from './assumptionsKey'

/** Los supuestos, uno por línea con la fase que lo dejó (ES-C). */
export function AssumptionList({ assumptions }: { assumptions: Assumption[] }) {
  return (
    <ul className="assumption-list" aria-label="Supuestos">
      {assumptions.map((a) => (
        <li key={`${a.stageKey}:${a.text}`}>
          {a.text} <code className="muted small">{a.stageKey}</code>
        </li>
      ))}
    </ul>
  )
}

/**
 * Lo que los agentes de la ejecución supusieron sin poder comprobarlo (ES-C). No para nada: está
 * para revisarlo antes de fusionar. Sin supuestos, no se enseña.
 */
export function AssumptionsCard({ runId }: { runId: string }) {
  const data = useQuery({ queryKey: assumptionsKey(runId), queryFn: () => api.assumptions(runId) })
  const assumptions = data.data ?? []
  if (assumptions.length === 0) return null
  return (
    <section className="card assumptions-card" aria-labelledby="assumptions-title">
      <h2 id="assumptions-title">Supuestos para revisar ({assumptions.length})</h2>
      <p className="muted small">
        Lo que los agentes supusieron sin poder comprobarlo. No ha parado nada: míralo antes de
        fusionar.
      </p>
      <AssumptionList assumptions={assumptions} />
    </section>
  )
}
