import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type GitHubAccess, type GitHubStatus, type WebhookDelivery } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { formatDateTime } from '../../format'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { LinesSkeleton } from '../ui/Skeleton'
import {
  ACCESS,
  DELIVERIES_KEY,
  DELIVERY,
  EVENTS,
  GITHUB_KEY,
  PERMISSIONS,
  useGitHub,
} from './github'

export function AccessPill({ access }: { access: GitHubAccess }) {
  return <Pill tone={ACCESS[access].tone}>{ACCESS[access].label}</Pill>
}

/**
 * Ajustes → GitHub: el estado de la App, cómo crearla, sus instalaciones con el botón
 * Sincronizar, el enlace de cada repositorio de Skynet y las últimas entregas de webhooks.
 */
export function GitHubSettings() {
  const status = useGitHub()
  return (
    <div className="settings">
      <ErrorMessage error={status.error} />
      {status.isPending && <LinesSkeleton label="la conexión con GitHub" />}
      {status.data && (
        <>
          <AppSection status={status.data} />
          <SetupSection open={!status.data.configured} />
          <InstallationsSection status={status.data} />
          <RepositoriesSection status={status.data} />
          {status.data.configured && <DeliveriesSection />}
        </>
      )}
    </div>
  )
}

function AppSection({ status }: { status: GitHubStatus }) {
  return (
    <section className="card settings-section" aria-labelledby="github-app-title">
      <div className="github-head">
        <h2 id="github-app-title">GitHub App</h2>
        {!status.configured ? (
          <Pill tone="warn">Sin configurar</Pill>
        ) : status.app ? (
          <Pill tone="ok">Conectada</Pill>
        ) : (
          <Pill tone="bad">Sin respuesta</Pill>
        )}
      </div>
      {!status.configured && (
        <p>
          Faltan{' '}
          {status.missing.map((v, i) => (
            <span key={v}>
              {i > 0 && ', '}
              <code>{v}</code>
            </span>
          ))}
          . Sigue los pasos de abajo y reinicia el control plane.
        </p>
      )}
      {status.app && (
        <dl className="repo-facts small">
          <div>
            <dt>App</dt>
            <dd>
              <a href={status.app.htmlUrl} target="_blank" rel="noreferrer">
                {status.app.slug}
              </a>{' '}
              de {status.app.owner}
            </dd>
          </div>
          <div>
            <dt>Id</dt>
            <dd>{status.appId}</dd>
          </div>
          <div>
            <dt>API</dt>
            <dd>
              <code>{status.apiUrl}</code>
            </dd>
          </div>
          <div>
            <dt>Comprobada</dt>
            <dd>{formatDateTime(status.checkedAt)}</dd>
          </div>
        </dl>
      )}
      {status.configured && status.error && (
        <p className="error" role="alert">
          {status.error}
        </p>
      )}
    </section>
  )
}

function SetupSection({ open }: { open: boolean }) {
  return (
    <section className="card settings-section" aria-labelledby="github-setup-title">
      <details open={open}>
        <summary>
          <h2 id="github-setup-title" className="github-summary">
            Crear la App
          </h2>
        </summary>
        <ol className="github-steps">
          <li>
            En GitHub, ve a{' '}
            <strong>Settings → Developer settings → GitHub Apps → New GitHub App</strong>. Ponle un
            nombre (por ejemplo <code>skynet-tu-usuario</code>) y cualquier URL de inicio.
          </li>
          <li>
            Activa el webhook con esta URL, que es la de tu túnel al puerto de webhooks (
            <code>SKYNET_WEBHOOK_PORT</code>, 8082), y content type <code>application/json</code>:
            <CopyValue value="https://<tu-túnel>/api/github/webhooks" label="URL del webhook" />
            Como secreto, un texto largo al azar, que será <code>SKYNET_GITHUB_WEBHOOK_SECRET</code>
            :
            <CopyValue value="openssl rand -hex 32" label="orden para generar el secreto" />
          </li>
          <li>
            Permisos del repositorio, y ninguno más (sin <code>Workflows</code>: ningún agente puede
            tocar <code>.github/workflows</code>):
            <table className="table small">
              <caption className="visually-hidden">Permisos de la App</caption>
              <thead>
                <tr>
                  <th scope="col">Permiso</th>
                  <th scope="col">Nivel</th>
                  <th scope="col">Para qué</th>
                </tr>
              </thead>
              <tbody>
                {PERMISSIONS.map((p) => (
                  <tr key={p.name}>
                    <td>{p.name}</td>
                    <td>{p.level}</td>
                    <td className="muted">{p.why}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </li>
          <li>
            Suscríbela a estos eventos: {EVENTS.join(', ')}. <code>installation</code> llega
            siempre.
          </li>
          <li>
            Que solo se pueda instalar en tu cuenta. Crea la App, genera una clave privada (un{' '}
            <code>.pem</code>) e instálala solo en los repositorios con los que trabaje Skynet.
          </li>
          <li>
            En <code>deploy/.env</code>, y reinicia con <code>docker compose up -d</code>:
            <CopyValue
              value={
                'SKYNET_GITHUB_APP_ID=<id de la App>\n' +
                'SKYNET_GITHUB_PRIVATE_KEY_FILE=/ruta/a/la-clave.pem\n' +
                'SKYNET_GITHUB_WEBHOOK_SECRET=<el secreto>'
              }
              label="variables de la App"
            />
          </li>
        </ol>
        <p className="hint">
          La clave privada no se guarda en la base de datos ni sale por la API. Más detalles en{' '}
          <code>docs/github.md</code>.
        </p>
      </details>
    </section>
  )
}

/** Un valor para pegar en GitHub o en la configuración, con su botón de copiar. */
function CopyValue({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    await navigator.clipboard?.writeText(value)
    setCopied(true)
  }
  return (
    <div className="copy-value">
      <pre>
        <code>{value}</code>
      </pre>
      <Button
        size="sm"
        variant="secondary"
        aria-label={`Copiar ${label}`}
        onClick={() => void copy()}
      >
        {copied ? 'Copiado' : 'Copiar'}
      </Button>
    </div>
  )
}

function InstallationsSection({ status }: { status: GitHubStatus }) {
  const queryClient = useQueryClient()
  const sync = useMutation({
    mutationFn: api.syncGitHub,
    onSuccess: (next) => queryClient.setQueryData(GITHUB_KEY, next),
  })
  return (
    <section className="card settings-section" aria-labelledby="github-installations-title">
      <div className="github-head">
        <h2 id="github-installations-title">Instalaciones</h2>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => sync.mutate()}
          disabled={!status.configured || sync.isPending}
        >
          {sync.isPending ? 'Sincronizando…' : 'Sincronizar'}
        </Button>
      </div>
      <ErrorMessage error={sync.error} />
      {sync.isSuccess && (
        <p className="saved" role="status">
          Sincronizado.
        </p>
      )}
      {status.installations.length === 0 ? (
        <p className="muted small">
          {status.configured
            ? 'La App no está instalada en ninguna cuenta, o aún no se ha sincronizado.'
            : 'Configura la App para ver dónde está instalada.'}
        </p>
      ) : (
        <ul className="github-installations">
          {status.installations.map((i) => (
            <li key={i.id}>
              <p>
                <strong>{i.account}</strong>{' '}
                <span className="muted small">
                  ({i.accountType === 'Organization' ? 'organización' : 'usuario'},{' '}
                  {i.repositorySelection === 'all'
                    ? 'todos sus repositorios'
                    : i.repositories.length === 1
                      ? '1 repositorio elegido'
                      : `${i.repositories.length} repositorios elegidos`}
                  )
                </span>{' '}
                {i.suspendedAt && <Pill tone="warn">Suspendida</Pill>}
              </p>
              <ul className="tool-chips">
                {i.repositories.map((r) => (
                  <li key={r.id}>
                    <code className="tool-chip">{r.fullName}</code>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function RepositoriesSection({ status }: { status: GitHubStatus }) {
  return (
    <section className="card settings-section" aria-labelledby="github-repos-title">
      <h2 id="github-repos-title">Repositorios de Skynet</h2>
      {status.repositories.length === 0 ? (
        <p className="muted small">No hay repositorios registrados.</p>
      ) : (
        <div className="table-scroll">
          <table className="table small">
            <caption className="visually-hidden">
              Repositorios de Skynet y su acceso en GitHub
            </caption>
            <thead>
              <tr>
                <th scope="col">Repositorio</th>
                <th scope="col">Proyecto</th>
                <th scope="col">En GitHub</th>
                <th scope="col">Acceso</th>
              </tr>
            </thead>
            <tbody>
              {status.repositories.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  <td>{r.projectKey}</td>
                  <td>{r.fullName ? <code>{r.fullName}</code> : '—'}</td>
                  <td>
                    <AccessPill access={r.access} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function DeliveriesSection() {
  const deliveries = useQuery({
    queryKey: DELIVERIES_KEY,
    queryFn: () => api.gitHubDeliveries(50),
    refetchInterval: 10_000,
  })
  return (
    <section className="card settings-section" aria-labelledby="github-deliveries-title">
      <h2 id="github-deliveries-title">Últimas entregas</h2>
      <ErrorMessage error={deliveries.error} />
      {deliveries.isPending && <LinesSkeleton label="las entregas" />}
      {deliveries.data?.length === 0 && (
        <p className="muted small">
          Todavía no ha llegado ningún webhook. GitHub manda un <code>ping</code> al activarlo.
        </p>
      )}
      {deliveries.data && deliveries.data.length > 0 && (
        <div className="table-scroll">
          <table className="table small">
            <caption className="visually-hidden">Últimas entregas de webhooks de GitHub</caption>
            <thead>
              <tr>
                <th scope="col">Recibida</th>
                <th scope="col">Evento</th>
                <th scope="col">Repositorio</th>
                <th scope="col">Estado</th>
                <th scope="col">Detalle</th>
              </tr>
            </thead>
            <tbody>
              {deliveries.data.map((d) => (
                <DeliveryRow key={d.deliveryId} delivery={d} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function DeliveryRow({ delivery: d }: { delivery: WebhookDelivery }) {
  const status = DELIVERY[d.status]
  const detail =
    d.status === 'PROCESSED'
      ? d.events === 1
        ? '1 evento'
        : `${d.events} eventos`
      : (d.error ?? (d.attempts > 0 ? `${d.attempts} intentos` : ''))
  return (
    <tr>
      <td>{formatDateTime(d.receivedAt)}</td>
      <td>
        <code>{d.action ? `${d.event}.${d.action}` : d.event}</code>
      </td>
      <td>{d.repository ?? '—'}</td>
      <td>
        <Pill tone={status.tone}>{status.label}</Pill>
      </td>
      <td className="github-detail" title={d.deliveryId}>
        {detail}
      </td>
    </tr>
  )
}
