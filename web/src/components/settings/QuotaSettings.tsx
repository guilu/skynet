import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, type QuotaSettings } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { providerName } from '../../format'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { LinesSkeleton } from '../ui/Skeleton'

const QUOTA_SETTINGS_KEY = ['quota', 'settings'] as const
const QUOTA_WAITS_KEY = ['quota', 'waits'] as const

/** Esperas máximas que se ofrecen; 0 es sin límite. */
const MAX_WAIT_OPTIONS = [
  { hours: 6, label: '6 h' },
  { hours: 24, label: '24 h' },
  { hours: 0, label: 'Sin límite' },
]

/**
 * Ajustes → Ejecución (CQ-A): «Continuar tras la cuota». Cuando la cuenta de un CLI se queda sin
 * su ventana de 5 h o semanal, la fase espera a que se renueve y sigue donde lo dejó.
 */
export function QuotaSettingsSection() {
  const settings = useQuery({ queryKey: QUOTA_SETTINGS_KEY, queryFn: api.quotaSettings })
  return (
    <div className="settings">
      <section className="card settings-section" aria-labelledby="quota-title">
        <div className="github-head">
          <h2 id="quota-title">Continuar tras la cuota</h2>
          {settings.data &&
            (settings.data.enabled ? (
              <Pill tone="ok">Encendido</Pill>
            ) : (
              <Pill tone="neutral">Apagado</Pill>
            ))}
        </div>
        <p className="muted small">
          Si un agente se queda sin la cuota de su cuenta (la ventana de 5 h o la semanal), su fase
          se pone en pausa hasta que se renueve y sigue en el mismo worktree, sin gastar intentos de{' '}
          <code>retry</code>. Un agente puede decir otra cosa en el YAML con{' '}
          <code>onQuota: fail</code>.
        </p>
        <ErrorMessage error={settings.error} />
        {settings.isPending && <LinesSkeleton label="los ajustes de la cuota" />}
        {settings.data && <QuotaForm key={settings.data.updatedAt} settings={settings.data} />}
      </section>
      <QuotaWaitsSection />
    </div>
  )
}

function QuotaForm({ settings }: { settings: QuotaSettings }) {
  const queryClient = useQueryClient()
  const [maxWaitHours, setMaxWaitHours] = useState(settings.maxWaitHours)
  const [maxWaits, setMaxWaits] = useState(String(settings.maxWaits))
  const save = useMutation({
    mutationFn: (body: Parameters<typeof api.saveQuotaSettings>[0]) => api.saveQuotaSettings(body),
    onSuccess: (saved) => queryClient.setQueryData(QUOTA_SETTINGS_KEY, saved),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate({ maxWaitHours, maxWaits: Number(maxWaits) })
  }
  return (
    <>
      <div className="triggers-switch">
        <p>
          {settings.enabled
            ? 'Encendido: las fases esperan a que se renueve la cuota.'
            : 'Apagado: un agente sin cuota hace fallar su fase, como antes.'}
        </p>
        <Button
          variant={settings.enabled ? 'secondary-danger' : 'success'}
          size="sm"
          disabled={save.isPending}
          onClick={() => save.mutate({ enabled: !settings.enabled })}
        >
          {settings.enabled ? 'Apagar' : 'Encender'}
        </Button>
      </div>
      <form className="form" onSubmit={submit}>
        <fieldset className="quota-max-wait">
          <legend>Espera máxima</legend>
          {MAX_WAIT_OPTIONS.map((o) => (
            <label key={o.hours}>
              <input
                type="radio"
                name="max-wait"
                checked={maxWaitHours === o.hours}
                onChange={() => setMaxWaitHours(o.hours)}
              />{' '}
              {o.label}
            </label>
          ))}
          <span className="hint">
            Si la cuota se renueva más tarde (la semanal, casi siempre), la fase falla con el
            motivo.
          </span>
        </fieldset>
        <label>
          Esperas por fase
          <input
            type="number"
            min={1}
            max={20}
            value={maxWaits}
            onChange={(e) => setMaxWaits(e.target.value)}
          />
          <span className="hint">
            Para que un proveedor que no renueva su ventana no deje la fase esperando siempre.
          </span>
        </label>
        <div>
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
        <ErrorMessage error={save.error} />
      </form>
    </>
  )
}

/** Las fases que esperan ahora a la cuota, con un enlace a su ejecución. */
function QuotaWaitsSection() {
  const waits = useQuery({
    queryKey: QUOTA_WAITS_KEY,
    queryFn: api.quotaWaits,
    refetchInterval: 60_000,
  })
  return (
    <section className="card settings-section" aria-labelledby="quota-waits-title">
      <h2 id="quota-waits-title">En pausa ahora</h2>
      <ErrorMessage error={waits.error} />
      {waits.data && waits.data.length === 0 && (
        <p className="muted small">Ninguna fase espera a la cuota.</p>
      )}
      {waits.data && waits.data.length > 0 && (
        <ul className="trigger-list">
          {waits.data.map((w) => (
            <li key={`${w.workflowRunId}-${w.stageKey}-${w.attempt}`}>
              <Link to={`/runs/${w.workflowRunId}?stage=${encodeURIComponent(w.stageKey)}`}>
                {w.workItemKey} · {w.stageKey}
              </Link>{' '}
              <span className="muted small">
                {providerName(w.provider)} · sigue a las{' '}
                {new Date(w.notBefore).toLocaleTimeString('es-ES', {
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
