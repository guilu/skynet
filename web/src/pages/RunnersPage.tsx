import { useQuery } from '@tanstack/react-query'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'

/** Runners registrados, su carga y si siguen enviando latidos. */
export function RunnersPage() {
  const runners = useQuery({ queryKey: ['runners'], queryFn: api.runners, refetchInterval: 15_000 })
  return (
    <>
      <h1>Runners</h1>
      <ErrorMessage error={runners.error} />
      {runners.data?.length === 0 && (
        <p className="muted">
          No hay runners registrados. Arranca uno en la máquina de los repositorios (README, «Runner
          local»).
        </p>
      )}
      {!!runners.data?.length && (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Nombre</th>
                <th scope="col">Estado</th>
                <th scope="col" className="num">
                  Agentes / capacidad
                </th>
                <th scope="col">Último latido</th>
                <th scope="col">Versiones</th>
              </tr>
            </thead>
            <tbody>
              {runners.data.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  <td>
                    <StatusBadge status={r.status} />
                  </td>
                  <td className="num">
                    {r.activeAgents} / {r.capacity}
                  </td>
                  <td>{formatDateTime(r.lastHeartbeatAt)}</td>
                  <td className="muted small">
                    runner {r.runnerVersion ?? '—'} · Claude Code {r.providerVersion ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
