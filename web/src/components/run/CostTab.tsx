import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, type AgentRun } from '../../api'
import { formatCost, formatNumber, isTerminal, providerName } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { formatPercent, windowLabel } from '../usage/quotaFormat'

const KIND_LABELS: Record<AgentRun['kind'], string> = {
  START: 'Inicio',
  RESUME: 'Reanudación',
  RETRY: 'Reintento',
  FORK: 'Bifurcación',
}

/**
 * Tokens y coste de esta invocación y de la sesión entera. El coste acumulado lo da el proveedor
 * por sesión; el de cada invocación es la diferencia con la anterior.
 */
export function CostTab({ agent }: { agent: AgentRun }) {
  const conversation = useQuery({
    queryKey: ['conversation', agent.id],
    queryFn: () => api.conversation(agent.id),
    enabled: agent.providerSessionId != null,
  })
  const turns = conversation.data?.turns ?? []

  return (
    <>
      <h3>Esta invocación</h3>
      <dl className="agent-facts small">
        <Fact label="Entrada" value={formatNumber(agent.inputTokens)} />
        <Fact label="Salida" value={formatNumber(agent.outputTokens)} />
        <Fact label="Caché leída" value={formatNumber(agent.cacheReadTokens)} />
        <Fact label="Caché escrita" value={formatNumber(agent.cacheCreationTokens)} />
        <Fact label="Coste" value={formatCost(agent.costUsd)} />
        <Fact label="Acumulado de la sesión" value={formatCost(agent.costUsdCumulative)} />
      </dl>
      {agent.costUsd == null && (
        <p className="muted small">El coste llega con el resultado final de la invocación.</p>
      )}
      {agent.provider !== 'command' && <QuotaSection agent={agent} />}
      <ErrorMessage error={conversation.error} />
      {turns.length > 1 && (
        <>
          <h3>Sesión</h3>
          <table className="table small">
            <caption className="visually-hidden">Invocaciones de la sesión</caption>
            <thead>
              <tr>
                <th scope="col">#</th>
                <th scope="col">Tipo</th>
                <th scope="col">Estado</th>
                <th scope="col">Tokens (entrada · salida)</th>
                <th scope="col">Coste</th>
                <th scope="col">Acumulado</th>
              </tr>
            </thead>
            <tbody>
              {turns.map((t, i) => (
                <tr key={t.agent.id} aria-current={t.agent.id === agent.id ? 'true' : undefined}>
                  <td>{i + 1}</td>
                  <td>
                    <Link to={`/runs/${t.workflowRunId}?agent=${t.agent.id}&tab=cost`}>
                      {KIND_LABELS[t.agent.kind]}
                    </Link>
                  </td>
                  <td>
                    <StatusBadge status={t.agent.status} />
                  </td>
                  <td>
                    {formatNumber(t.agent.inputTokens)} · {formatNumber(t.agent.outputTokens)}
                  </td>
                  <td>{formatCost(t.agent.costUsd)}</td>
                  <td>{formatCost(t.agent.costUsdCumulative)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colSpan={4}>
                  Total de la sesión
                </th>
                <td colSpan={2}>{formatCost(sum(turns.map((t) => t.agent.costUsd)))}</td>
              </tr>
            </tfoot>
          </table>
        </>
      )}
    </>
  )
}

/** La parte de las ventanas de cuota de su CLI que se atribuye a esta invocación. */
function QuotaSection({ agent }: { agent: AgentRun }) {
  return (
    <>
      <h3>Cuota</h3>
      {agent.quota.length > 0 ? (
        <>
          <dl className="agent-facts small">
            {agent.quota.map((q) => (
              <Fact
                key={`${q.provider}-${q.bucket}-${q.window}`}
                label={`${providerName(q.provider)} · ${windowLabel(q.window)}`}
                value={`${formatPercent(q.share)} (${q.method === 'measured' ? 'medido' : 'repartido'})`}
              />
            ))}
          </dl>
          <p className="muted small">
            «Medido»: el agente corrió solo y la subida de la cuota es suya. «Repartido»: corrió a
            la vez que otros y la subida se reparte según el coste de cada uno.
          </p>
        </>
      ) : (
        <p className="muted small">
          {isTerminal(agent.status)
            ? 'Sin cuota atribuida: su CLI no informó de ninguna subida mientras corría.'
            : 'La cuota se atribuye cuando termina la invocación.'}
        </p>
      )}
    </>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="fact">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

/** Suma de los costes conocidos; null si no hay ninguno. */
function sum(values: (number | null)[]): number | null {
  const known = values.filter((v): v is number => v != null)
  return known.length === 0 ? null : known.reduce((a, b) => a + b, 0)
}
