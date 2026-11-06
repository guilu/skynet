import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { HeartPulse } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api, type HealthCheck, type Repository } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'

const checksKey = (repositoryId: string) => ['health-checks', repositoryId] as const

/**
 * En la ficha de un repositorio (DL-E): la comprobación de salud de cada entorno. La URL la pone
 * el admin aquí; nunca sale del evento de despliegue.
 */
export function HealthCheckSection({
  repository: r,
  editable,
}: {
  repository: Repository
  editable: boolean
}) {
  const queryClient = useQueryClient()
  const checks = useQuery({ queryKey: checksKey(r.id), queryFn: () => api.healthChecks(r.id) })
  const [editing, setEditing] = useState<HealthCheck | 'new' | null>(null)
  const remove = useMutation({
    mutationFn: (environment: string) => api.deleteHealthCheck(r.id, environment),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: checksKey(r.id) }),
  })
  return (
    <section className="repo-section" aria-label={`Salud de los despliegues de ${r.name}`}>
      <div className="repo-section-head">
        <h3>
          <HeartPulse size={18} strokeWidth={2.5} aria-hidden="true" /> Salud de los despliegues
        </h3>
        {editable && (
          <Button
            size="sm"
            variant="secondary"
            aria-label={`Nueva comprobación de salud en ${r.name}`}
            onClick={() => setEditing('new')}
          >
            Nueva comprobación
          </Button>
        )}
      </div>
      <ErrorMessage error={checks.error} />
      <ErrorMessage error={remove.error} />
      {checks.data?.length === 0 && (
        <p className="muted small">
          Ninguna: lo desplegado no se comprueba y su salud queda sin comprobar.
        </p>
      )}
      {checks.data && checks.data.length > 0 && (
        <ul className="trigger-list">
          {checks.data.map((c) => (
            <li key={c.environment} className="trigger-item">
              <div className="trigger-main">
                <p className="small">
                  <strong>{c.environment}</strong>: <code>{c.url}</code>{' '}
                  {!c.enabled && <span className="tag">apagada</span>}
                </p>
                <p className="muted small">
                  {c.attempts} intentos cada {c.intervalSeconds} s, tras {c.initialDelaySeconds} s ·
                  plazo {c.timeoutSeconds} s{c.expectSha && ' · exige el SHA'}
                </p>
              </div>
              {editable && (
                <div className="trigger-actions">
                  <Button
                    size="sm"
                    variant="secondary"
                    aria-label={`Editar la comprobación de ${c.environment}`}
                    onClick={() => setEditing(c)}
                  >
                    Editar
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    aria-label={`Quitar la comprobación de ${c.environment}`}
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(c.environment)}
                  >
                    Quitar
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      <Sheet
        open={editing != null}
        onOpenChange={(open) => !open && setEditing(null)}
        title={
          editing === 'new' || editing == null
            ? `Nueva comprobación en ${r.name}`
            : `Comprobación de ${editing.environment}`
        }
        description="Tras cada despliegue que termina bien en el entorno, Skynet hace un GET a esta URL hasta que responde 2xx o se agotan los intentos. No revierte nada."
      >
        {editing != null && (
          <HealthCheckForm
            repositoryId={r.id}
            check={editing === 'new' ? null : editing}
            onDone={() => setEditing(null)}
          />
        )}
      </Sheet>
    </section>
  )
}

function HealthCheckForm({
  repositoryId,
  check,
  onDone,
}: {
  repositoryId: string
  check: HealthCheck | null
  onDone: () => void
}) {
  const queryClient = useQueryClient()
  const [environment, setEnvironment] = useState(check?.environment ?? 'omarchy')
  const [url, setUrl] = useState(check?.url ?? '')
  const [attempts, setAttempts] = useState(String(check?.attempts ?? 6))
  const [intervalSeconds, setIntervalSeconds] = useState(String(check?.intervalSeconds ?? 10))
  const [initialDelaySeconds, setInitialDelaySeconds] = useState(
    String(check?.initialDelaySeconds ?? 15),
  )
  const [timeoutSeconds, setTimeoutSeconds] = useState(String(check?.timeoutSeconds ?? 5))
  const [expectSha, setExpectSha] = useState(check?.expectSha ?? false)
  const [enabled, setEnabled] = useState(check?.enabled ?? true)
  const save = useMutation({
    mutationFn: () =>
      api.saveHealthCheck(repositoryId, environment.trim(), {
        url: url.trim(),
        attempts: Number(attempts),
        intervalSeconds: Number(intervalSeconds),
        initialDelaySeconds: Number(initialDelaySeconds),
        timeoutSeconds: Number(timeoutSeconds),
        expectSha,
        enabled,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: checksKey(repositoryId) })
      onDone()
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate()
  }
  const number = (
    label: string,
    value: string,
    set: (v: string) => void,
    min: number,
    max: number,
  ) => (
    <label>
      {label}
      <input
        type="number"
        min={min}
        max={max}
        value={value}
        onChange={(e) => set(e.target.value)}
        required
      />
    </label>
  )
  return (
    <form className="form" onSubmit={submit}>
      <label>
        Entorno
        <input
          value={environment}
          onChange={(e) => setEnvironment(e.target.value)}
          readOnly={check != null}
          required
        />
        <span className="hint">El mismo nombre que manda el listener (por defecto, omarchy).</span>
      </label>
      <label>
        URL
        <input
          type="url"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="http://omarchy:8081/actuator/health"
          required
        />
        <span className="hint">http(s), sin usuario. No sigue redirecciones.</span>
      </label>
      {number('Intentos', attempts, setAttempts, 1, 30)}
      {number('Segundos entre intentos', intervalSeconds, setIntervalSeconds, 1, 600)}
      {number('Espera inicial (s)', initialDelaySeconds, setInitialDelaySeconds, 0, 600)}
      {number('Plazo de cada intento (s)', timeoutSeconds, setTimeoutSeconds, 1, 60)}
      <label className="switch">
        <input
          type="checkbox"
          checked={expectSha}
          onChange={(e) => setExpectSha(e.target.checked)}
        />
        <span>La respuesta tiene que traer el SHA desplegado (p. ej. /actuator/info)</span>
      </label>
      <label className="switch">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        <span>Activa</span>
      </label>
      <div className="form-actions">
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? 'Guardando…' : 'Guardar'}
        </Button>
      </div>
      <ErrorMessage error={save.error} />
    </form>
  )
}
