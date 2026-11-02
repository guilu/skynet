import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, type TriggerLaunch, type TriggerLaunchStatus, type TriggerSettings } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { formatDateTime, type StatusTone } from '../../format'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { LinesSkeleton } from '../ui/Skeleton'

const TRIGGER_SETTINGS_KEY = ['triggers', 'settings'] as const
const TRIGGER_LAUNCHES_KEY = ['triggers', 'launches'] as const

const LAUNCH_STATUS: Record<TriggerLaunchStatus, { tone: StatusTone; label: string }> = {
  QUEUED: { tone: 'active', label: 'Recibida' },
  LAUNCHED: { tone: 'active', label: 'Lanzada' },
  FINISHED: { tone: 'ok', label: 'Terminada' },
  DUPLICATE: { tone: 'neutral', label: 'Ya en marcha' },
  REJECTED: { tone: 'bad', label: 'Rechazada' },
  DONE: { tone: 'ok', label: 'Hecha' },
}

/** Una lista escrita separada por comas, espacios o saltos de línea. */
function splitList(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((v) => v.trim())
    .filter(Boolean)
}

/**
 * Ajustes → GitHub → Disparadores (DS): el interruptor general, quién puede lanzar, los
 * repositorios de órdenes, el freno diario y las últimas peticiones que han llegado de GitHub.
 */
export function TriggerSettingsSection() {
  const settings = useQuery({ queryKey: TRIGGER_SETTINGS_KEY, queryFn: api.triggerSettings })
  return (
    <section className="card settings-section" aria-labelledby="triggers-title">
      <div className="github-head">
        <h2 id="triggers-title">Disparadores</h2>
        {settings.data &&
          (settings.data.enabled ? (
            <Pill tone="ok">Activos</Pill>
          ) : (
            <Pill tone="neutral">Apagados</Pill>
          ))}
      </div>
      <p className="muted small">
        Lanzar workflows desde una etiqueta o un comentario <code>/skynet</code> en GitHub, y con
        los disparadores programados de cada repositorio (en su ficha, en el proyecto). Ver{' '}
        <code>docs/disparadores.md</code>.
      </p>
      <ErrorMessage error={settings.error} />
      {settings.isPending && <LinesSkeleton label="los disparadores" />}
      {settings.data && (
        <TriggerSettingsForm key={settings.data.updatedAt} settings={settings.data} />
      )}
      <LaunchesTable />
    </section>
  )
}

function TriggerSettingsForm({ settings }: { settings: TriggerSettings }) {
  const queryClient = useQueryClient()
  const [logins, setLogins] = useState(
    settings.authorizedByDefault ? '' : settings.authorizedLogins.join(', '),
  )
  const [orders, setOrders] = useState(settings.orderRepositories.join(', '))
  const [dailyLimit, setDailyLimit] = useState(String(settings.dailyLimit))
  const save = useMutation({
    mutationFn: (body: Parameters<typeof api.saveTriggerSettings>[0]) =>
      api.saveTriggerSettings(body),
    onSuccess: (saved) => queryClient.setQueryData(TRIGGER_SETTINGS_KEY, saved),
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const list = splitList(logins)
    save.mutate({
      // Vacío con las de la instalación: se quedan; vacío con una lista fijada: nadie.
      authorizedLogins: list.length === 0 && settings.authorizedByDefault ? undefined : list,
      orderRepositories: splitList(orders),
      dailyLimit: Number(dailyLimit),
    })
  }
  return (
    <>
      <div className="triggers-switch">
        <p>
          {settings.enabled
            ? 'Encendidos: las etiquetas, los comentarios y los programados lanzan.'
            : 'Apagados: nada se lanza desde GitHub ni a su hora. Lo que llegue queda rechazado.'}
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
      <p className="small">
        Hoy van <strong>{settings.launchedToday}</strong> de <strong>{settings.dailyLimit}</strong>{' '}
        ejecuciones lanzadas desde GitHub.
      </p>
      <form className="form" onSubmit={submit}>
        <label>
          Quién puede lanzar (logins de GitHub)
          <input
            value={logins}
            onChange={(e) => setLogins(e.target.value)}
            placeholder={settings.authorizedLogins.join(', ') || 'guilu'}
          />
          <span className="hint">
            {settings.authorizedByDefault
              ? `Vacío: las cuentas donde está instalada la App (${
                  settings.authorizedLogins.join(', ') || 'ninguna'
                }).`
              : 'Tienen que estarlo quien etiqueta o comenta y quien escribió la issue. Vacío: nadie.'}
          </span>
        </label>
        <label>
          Repositorios de órdenes
          <input
            value={orders}
            onChange={(e) => setOrders(e.target.value)}
            placeholder="guilu/skynet-commands"
          />
          <span className="hint">
            <code>owner/nombre</code>, con la etiqueta <code>skynet:run</code> y el bloque{' '}
            <code>skynet-order</code>. No tienen que estar en Skynet.
          </span>
        </label>
        <label>
          Ejecuciones al día desde GitHub
          <input
            type="number"
            min={0}
            max={1000}
            value={dailyLimit}
            onChange={(e) => setDailyLimit(e.target.value)}
            required
          />
          <span className="hint">Desde las 00:00 en Madrid. 0: ninguna.</span>
        </label>
        <div className="form-actions">
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
        <ErrorMessage error={save.error} />
      </form>
    </>
  )
}

function LaunchesTable() {
  const launches = useQuery({
    queryKey: TRIGGER_LAUNCHES_KEY,
    queryFn: () => api.triggerLaunches(20),
    refetchInterval: 10_000,
  })
  return (
    <>
      <h3 className="triggers-subtitle">Últimas peticiones</h3>
      <ErrorMessage error={launches.error} />
      {launches.isPending && <LinesSkeleton label="las peticiones" />}
      {launches.data?.length === 0 && (
        <p className="muted small">Todavía no ha llegado ninguna etiqueta ni orden de GitHub.</p>
      )}
      {launches.data && launches.data.length > 0 && (
        <div className="table-scroll">
          <table className="table small">
            <caption className="visually-hidden">Últimas peticiones desde GitHub</caption>
            <thead>
              <tr>
                <th scope="col">Recibida</th>
                <th scope="col">Desde</th>
                <th scope="col">Qué</th>
                <th scope="col">Quién</th>
                <th scope="col">Estado</th>
                <th scope="col">Detalle</th>
              </tr>
            </thead>
            <tbody>
              {launches.data.map((l) => (
                <LaunchRow key={l.id} launch={l} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function LaunchRow({ launch: l }: { launch: TriggerLaunch }) {
  const status = LAUNCH_STATUS[l.status]
  const where = `${l.source}#${l.issueNumber}`
  return (
    <tr>
      <td>{formatDateTime(l.createdAt)}</td>
      <td>
        {l.issueUrl ? (
          <a href={l.issueUrl} target="_blank" rel="noreferrer">
            {where}
          </a>
        ) : (
          where
        )}
      </td>
      <td>
        <code>{l.command ?? l.label}</code>
      </td>
      <td>{l.sender ? `@${l.sender}` : '—'}</td>
      <td>
        <Pill tone={status.tone}>{status.label}</Pill>
      </td>
      <td className="github-detail">
        {l.workflowRunId ? (
          <Link to={`/runs/${l.workflowRunId}`}>Ver la ejecución</Link>
        ) : (
          (l.reason ?? '')
        )}
        {l.noticeError && (
          <span className="muted"> · El aviso en GitHub falló: {l.noticeError}</span>
        )}
      </td>
    </tr>
  )
}
