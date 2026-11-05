import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { api, type UsageWindow } from '../api'
import { Icon } from '../components/icons/Icon'
import type { IconName } from '../components/icons/registry'
import { formatPercent, quotaTone, windowLabel } from '../components/usage/quotaFormat'
import { formatUsd } from '../components/work/phase'
import { providerName, simulatedProviders } from '../format'
import { cn } from '../lib/cn'

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

/**
 * Administración (FS-F): la portada del área, con una tarjeta por sección y lo esencial de cada
 * una: qué CLIs hay, qué runners están en línea, lo gastado en 30 días con las ventanas de cuota a
 * la vista, los ajustes y lo archivado.
 */
export function AdminPage() {
  const runners = useQuery({
    queryKey: ['runners'],
    queryFn: () => api.runners(),
    refetchInterval: 15_000,
  })
  const kinds = useQuery({ queryKey: ['providers'], queryFn: api.providers })
  const metrics = useQuery({
    queryKey: ['dashboard', 'metrics', '30d'],
    queryFn: () => api.dashboardMetrics('30d', zone()),
  })
  const windows = useQuery({
    queryKey: ['usage', 'windows'],
    queryFn: () => api.usageWindows(),
    refetchInterval: 60_000,
  })

  const list = runners.data ?? []
  const online = list.filter((r) => r.status === 'ONLINE')
  const simulated = list.flatMap((r) =>
    simulatedProviders(r.providers).map((p) => `${p} en ${r.name}`),
  )
  const available = (kinds.data ?? []).filter((k) => k.onlineRunners > 0)
  const quota = topWindows(windows.data?.runners.flatMap((r) => r.windows) ?? [])

  return (
    <>
      <h1>Administración</h1>
      <p className="muted">
        Los medios de la fábrica: con qué CLIs, en qué máquinas y a qué coste.
      </p>
      <ul className="admin-grid">
        <AdminCard
          to="/providers"
          icon="provider"
          tone="primary"
          title="Proveedores"
          subtitle={kinds.data ? kinds.data.map((k) => k.displayName).join(', ') : '…'}
        >
          {kinds.data &&
            `${available.length} ${available.length === 1 ? 'CLI disponible' : 'CLIs disponibles'}`}
          {simulated.length > 0 && ` · simulado: ${simulated.join(', ')}`}
          {'. Qué hacer si uno se queda sin cuota.'}
        </AdminCard>
        <AdminCard
          to="/runners"
          icon="runners"
          tone="ok"
          title="Runners"
          subtitle={runners.data ? `${online.length} en línea` : '…'}
        >
          {online.length > 0
            ? online
                .map(
                  (r) =>
                    `${r.name} (${r.activeAgents} ${r.activeAgents === 1 ? 'agente' : 'agentes'})`,
                )
                .join(', ')
            : 'Ningún runner en línea.'}
        </AdminCard>
        <AdminCard
          to="/usage"
          icon="usage"
          tone="warn"
          title="Consumo"
          subtitle={metrics.data ? `${formatUsd(metrics.data.costUsd)} en 30 días` : '…'}
        >
          {quota.length > 0 ? (
            <ul className="admin-quota" aria-label="Ventanas de cuota">
              {quota.map((w) => (
                <li key={`${w.provider}-${w.bucket}-${w.window}`}>
                  <span>
                    {providerName(w.provider)} · {windowLabel(w.window).toLowerCase()}
                    <span className="muted"> {formatPercent(w.usedFraction)}</span>
                  </span>
                  <span className="admin-quota-bar" aria-hidden="true">
                    <i
                      className={`tone-${quotaTone(w.usedFraction)}`}
                      style={{ width: `${Math.min(100, Math.max(2, w.usedFraction * 100))}%` }}
                    />
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            'Las métricas de las ejecuciones y la cuota de cada CLI.'
          )}
        </AdminCard>
        <AdminCard
          to="/settings"
          icon="settings"
          tone="idle"
          title="Ajustes"
          subtitle="Apariencia, Skyny, GitHub, ejecución"
        >
          Colores, iconos, companion, GitHub App y disparadores.
        </AdminCard>
        <AdminCard
          to="/maintenance"
          icon="tool"
          tone="idle"
          title="Mantenimiento"
          subtitle="Archivo y limpieza"
        >
          Lo archivado, para restaurarlo o eliminarlo, y las entregas del webhook.
        </AdminCard>
      </ul>
    </>
  )
}

/** Las ventanas más gastadas, una por CLI y tipo de ventana, como mucho cuatro. */
function topWindows(windows: UsageWindow[]): UsageWindow[] {
  const best = new Map<string, UsageWindow>()
  for (const w of windows) {
    const key = `${w.provider}/${w.window}`
    const prev = best.get(key)
    if (!prev || w.usedFraction > prev.usedFraction) best.set(key, w)
  }
  return [...best.values()].sort((a, b) => b.usedFraction - a.usedFraction).slice(0, 4)
}

function AdminCard({
  to,
  icon,
  tone,
  title,
  subtitle,
  children,
}: {
  to: string
  icon: IconName
  tone: string
  title: string
  subtitle: string
  children: ReactNode
}) {
  return (
    <li className="card admin-card">
      <span className={cn('kpi-icon', `kpi-${tone}`)} aria-hidden="true">
        <Icon name={icon} size={22} strokeWidth={2.25} />
      </span>
      <div>
        <h2>
          <Link to={to} className="row-link">
            {title}
          </Link>
        </h2>
        <p className="muted small">{subtitle}</p>
        <div className="admin-card-body">{children}</div>
      </div>
    </li>
  )
}
