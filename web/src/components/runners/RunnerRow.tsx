import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FlaskConical, Server } from 'lucide-react'
import { useState } from 'react'
import { api, type ProviderInfo, type Runner } from '../../api'
import { formatStamp, providerName, shortVersion } from '../../format'
import { ArchiveMenu } from '../archive/Archive'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { Button } from '../ui/Button'
import { SIMULATED_HINT } from '../ui/SimulatedPill'

/**
 * Un runner en la lista: tarjeta ancha con su nombre, estado, carga, último latido y acciones y,
 * debajo, las versiones del runner y de cada CLI de agentes en chips (los simulados, en ámbar).
 */
export function RunnerRow({ runner: r }: { runner: Runner }) {
  return (
    <article className="card runner-row" aria-label={r.name}>
      <div className="runner-row-title">
        <span className="runner-badge" data-status={r.status} aria-hidden="true">
          <Server />
        </span>
        <strong className="runner-row-name">{r.name}</strong>
        <StatusBadge status={r.status} />
      </div>
      <dl className="runner-row-side">
        <div>
          <dt>Agentes</dt>
          <dd>
            <Capacity used={r.activeAgents} capacity={r.capacity} />
          </dd>
        </div>
        <div>
          <dt>Último latido</dt>
          <dd className="num">{r.lastHeartbeatAt ? formatStamp(r.lastHeartbeatAt) : '—'}</dd>
        </div>
      </dl>
      <div className="runner-row-actions">
        {r.archivedAt == null && <RevokeToken runner={r} />}
        <ArchiveMenu
          size="sm"
          target={{ kind: 'runner', id: r.id }}
          name={r.name}
          archivedAt={r.archivedAt}
        />
      </div>
      <ul className="runner-versions" aria-label="Versiones">
        <li className="tag version-chip">
          <span>runner</span> <code>{r.runnerVersion ?? '—'}</code>
        </li>
        {r.providers.map((p) => (
          <VersionChip key={p.id} provider={p} />
        ))}
        {r.providers.length === 0 && (
          <li className="tag version-chip">sin proveedores de agentes</li>
        )}
      </ul>
    </article>
  )
}

function VersionChip({ provider: p }: { provider: ProviderInfo }) {
  const version = p.version ? shortVersion(p.version) : null
  const title = [p.version, p.simulated && SIMULATED_HINT].filter(Boolean).join(' · ')
  return (
    <li
      className={p.simulated ? 'tag tag-warn version-chip' : 'tag version-chip'}
      title={title || undefined}
    >
      {p.simulated && (
        <span className="version-chip-icon" aria-hidden="true">
          <FlaskConical />
        </span>
      )}
      <span>{providerName(p.id)}</span>
      {version && (
        <>
          {' '}
          <code>{version}</code>
        </>
      )}
      {p.simulated && <span className="visually-hidden">: simulado</span>}
    </li>
  )
}

/** Carga del runner: «1 / 2» y una barra que se llena con los agentes en marcha. */
function Capacity({ used, capacity }: { used: number; capacity: number }) {
  const ratio = capacity > 0 ? Math.min(1, used / capacity) : 0
  return (
    <span className="capacity">
      <span className="num">
        {used} / {capacity}
      </span>
      <span className="capacity-bar" aria-hidden="true">
        <span style={{ width: `${ratio * 100}%` }} data-full={ratio >= 1 || undefined} />
      </span>
    </span>
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
    <span className="small runner-revoke">
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
