import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { lazy, Suspense, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api, type MetricsPeriod, type RunMetrics } from '../api'
import { formatCompact, formatCost, formatDuration, formatNumber } from '../format'
import { cn } from '../lib/cn'
import { ErrorMessage } from './ErrorMessage'
import { Icon } from './icons/Icon'
import { useIconPack } from './icons/pack'
import type { IconName } from './icons/registry'
import { bucketTitle } from './metricsFormat'
import { TilesSkeleton } from './ui/Skeleton'
import { PERIODS, usePeriod } from './usePeriod'

const RunsChart = lazy(() => import('./RunsChart'))

/** Zona del navegador: los tramos del gráfico siguen su calendario. */
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

/** Selector 24 h · 7 días · 30 días: cambia a la vez las tarjetas y el gráfico. */
export function PeriodSelector() {
  const [period, choose] = usePeriod()
  return (
    <div className="segmented" role="group" aria-label="Periodo">
      {PERIODS.map((p) => (
        <button
          key={p.id}
          type="button"
          aria-pressed={p.id === period}
          onClick={() => choose(p.id)}
        >
          {p.label}
        </button>
      ))}
    </div>
  )
}

/**
 * Métricas de las ejecuciones creadas en el periodo elegido (24 h, 7 o 30 días): ocho tarjetas
 * con cuántas hay por estado, su duración mediana, tokens y coste, y un panel con las
 * ejecuciones por hora o por día. Las cifras de ejecuciones enlazan a la lista con el mismo
 * filtro. El selector del periodo está en la cabecera del dashboard. `between` va entre las
 * tarjetas y el gráfico.
 */
export function DashboardMetrics({ between }: { between?: ReactNode }) {
  const [period] = usePeriod()
  const metrics = useQuery({
    queryKey: ['dashboard', 'metrics', period],
    queryFn: () => api.dashboardMetrics(period, zone()),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  })

  const data = metrics.data
  return (
    <>
      <section className="dashboard-metrics" aria-labelledby="summary-title">
        <div className="section-head">
          <h2 id="summary-title">Resumen ({PERIOD_TEXT[period]})</h2>
          {data && (
            <Link to={runsLink(data.since)} className="section-link">
              Ver más detalles
              <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
            </Link>
          )}
        </div>
        <ErrorMessage error={metrics.error} />
        {metrics.isPending && <TilesSkeleton />}
        {data && <Tiles data={data} period={period} />}
      </section>
      {between}
      {data && <RunsChartFigure data={data} />}
    </>
  )
}

function runsLink(since: string, status: string[] = []) {
  const p = new URLSearchParams()
  status.forEach((s) => p.append('status', s))
  p.set('since', since)
  return `/runs?${p}`
}

interface Tile {
  label: string
  value: string
  icon: IconName
  tone: 'live' | 'bad' | 'ok' | 'warn' | 'idle' | 'primary'
  to?: string
  /** Línea secundaria bajo la cifra. */
  sub?: string
}

const PERIOD_TEXT: Record<MetricsPeriod, string> = {
  '24h': 'últimas 24 horas',
  '7d': 'últimos 7 días',
  '30d': 'últimos 30 días',
}

function Tiles({ data, period }: { data: RunMetrics; period: MetricsPeriod }) {
  const finished = data.succeeded + data.failed + data.cancelled
  const share = (n: number) =>
    finished > 0 ? `${Math.round((n / finished) * 100)} % de las terminadas` : undefined
  const tiles: Tile[] = [
    {
      label: 'Activas',
      value: formatNumber(data.active),
      icon: 'play',
      tone: 'live',
      sub: 'pendientes o en curso',
      to: runsLink(data.since, ['PENDING', 'RUNNING']),
    },
    {
      label: 'Completadas',
      value: formatNumber(data.succeeded),
      icon: 'status-ok',
      tone: 'ok',
      sub: share(data.succeeded),
      to: runsLink(data.since, ['SUCCEEDED']),
    },
    {
      label: 'Fallidas',
      value: formatNumber(data.failed),
      icon: 'status-error',
      tone: 'bad',
      sub: share(data.failed),
      to: runsLink(data.since, ['FAILED']),
    },
    {
      label: 'Coste',
      value: formatCost(data.costUsd),
      icon: 'cost',
      tone: 'warn',
      sub:
        data.costUsd != null && data.total > 0
          ? `${formatCost(data.costUsd / data.total)} por ejecución`
          : undefined,
    },
    {
      label: 'Ejecuciones',
      value: formatNumber(data.total),
      icon: 'stages',
      tone: 'primary',
      sub: PERIOD_TEXT[period],
      to: runsLink(data.since),
    },
    {
      label: 'Canceladas',
      value: formatNumber(data.cancelled),
      icon: 'status-cancelled',
      tone: 'idle',
      sub: share(data.cancelled),
      to: runsLink(data.since, ['CANCELLED']),
    },
    {
      label: 'Duración mediana',
      value:
        data.medianDurationSeconds == null
          ? '—'
          : formatDuration(Math.round(data.medianDurationSeconds * 1000)),
      icon: 'duration',
      tone: 'primary',
      sub: 'de las terminadas',
    },
    {
      label: 'Tokens',
      value: `${formatCompact(data.inputTokens)} / ${formatCompact(data.outputTokens)}`,
      icon: 'prompt',
      tone: 'primary',
      sub: 'entrada / salida',
    },
  ]
  // Con un paquete ilustrado, el dibujo va suelto y grande; con Lucide, sobre su baldosa de color.
  const art = useIconPack() !== 'lucide'
  return (
    <dl className="kpis dash-kpis">
      {tiles.map((t) => (
        <div key={t.label} className={cn('kpi kpi-main', `kpi-${t.tone}`)}>
          <span className={cn('kpi-icon', art && 'kpi-icon-art')} aria-hidden="true">
            <Icon name={t.icon} size={art ? 44 : 24} strokeWidth={2.5} />
          </span>
          <dt>{t.label}</dt>
          <dd>{t.to ? <Link to={t.to}>{t.value}</Link> : t.value}</dd>
          {t.sub && <dd className="kpi-sub">{t.sub}</dd>}
        </div>
      ))}
    </dl>
  )
}

/** Gráfico de ejecuciones por tramo (recharts, diferido) y la misma información en tabla. */
function RunsChartFigure({ data }: { data: RunMetrics }) {
  const buckets = data.buckets
  if (buckets.length === 0) return null
  const unit = data.bucket
  const max = Math.max(0, ...buckets.map((b) => b.total))
  return (
    <figure className="card runs-chart">
      <figcaption>
        <h2>Ejecuciones por {unit === 'hour' ? 'hora' : 'día'}</h2>
        <span className="chart-legend" aria-hidden="true">
          <span className="legend-ok">Completadas</span>
          <span className="legend-bad">Fallidas</span>
          <span className="legend-idle">Canceladas</span>
          <span className="legend-live">Activas</span>
        </span>
      </figcaption>
      <div
        className="chart-frame"
        role="img"
        aria-label={`Ejecuciones por ${unit === 'hour' ? 'hora' : 'día'}: ${data.total} en total, máximo ${max} en un tramo`}
      >
        <Suspense fallback={<div className="chart-placeholder" />}>
          <RunsChart data={data} />
        </Suspense>
      </div>
      <details className="chart-table">
        <summary>Ver como tabla</summary>
        <table className="table small">
          <thead>
            <tr>
              <th scope="col">{unit === 'hour' ? 'Hora' : 'Día'}</th>
              <th scope="col">Ejecuciones</th>
              <th scope="col">Completadas</th>
              <th scope="col">Fallidas</th>
              <th scope="col">Canceladas</th>
              <th scope="col">Coste</th>
            </tr>
          </thead>
          <tbody>
            {buckets.map((b) => (
              <tr key={b.start}>
                <th scope="row">{bucketTitle(b, unit)}</th>
                <td>{b.total}</td>
                <td>{b.succeeded}</td>
                <td>{b.failed}</td>
                <td>{b.cancelled}</td>
                <td>{formatCost(b.costUsd)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
