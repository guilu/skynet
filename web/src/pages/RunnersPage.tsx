import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type Runner } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'
import { Button } from '../components/ui/Button'

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
                <th scope="col">
                  <span className="visually-hidden">Acciones</span>
                </th>
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
                  <td>
                    <RevokeToken runner={r} />
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

/**
 * Invalida el token del runner. El runner legítimo se vuelve a registrar solo con el secreto de
 * registro; quien solo tenga el token se queda fuera.
 */
function RevokeToken({ runner }: { runner: Runner }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const revoke = useMutation({
    mutationFn: () => api.revokeRunner(runner.id),
    onSuccess: () => {
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: ['runners'] })
    },
  })
  if (revoke.isSuccess && !confirming) {
    return (
      <span role="status" className="muted small">
        Token revocado
      </span>
    )
  }
  if (!confirming) {
    return (
      <Button variant="secondary-danger" size="sm" onClick={() => setConfirming(true)}>
        Revocar token…
      </Button>
    )
  }
  return (
    <span className="small">
      ¿Revocar el token de {runner.name}? Si es tu runner, se volverá a registrar solo.{' '}
      <Button
        variant="danger"
        size="sm"
        onClick={() => revoke.mutate()}
        disabled={revoke.isPending}
      >
        Sí, revocar
      </Button>{' '}
      <Button variant="secondary" size="sm" onClick={() => setConfirming(false)}>
        No
      </Button>
      <ErrorMessage error={revoke.error} />
    </span>
  )
}
