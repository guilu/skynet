import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  CircleCheck,
  CirclePlay,
  CircleX,
  Coins,
  Layers,
  Square,
  Timer,
  Type,
  type LucideIcon,
} from 'lucide-react'
import { lazy, Suspense } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, type MetricsPeriod, type RunMetrics } from '../api'
import { formatCost, formatDuration, formatNumber } from '../format'
import { cn } from '../lib/cn'
import { ErrorMessage } from './ErrorMessage'
import { bucketTitle } from './metricsFormat'

const RunsChart = lazy(() => import('./RunsChart'))

const PERIODS: { id: MetricsPeriod; label: string }[] = [
  { id: '24h', label: '24 horas' },
  { id: '7d', label: '7 días' },
  { id: '30d', label: '30 días' },
]

/** Zona del navegador: los tramos del gráfico siguen su calendario. */
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

/**
 * Métricas de las ejecuciones creadas en el periodo elegido (24 h, 7 o 30 días): cuántas hay por
 * estado, su duración mediana, tokens y coste, y las ejecuciones por hora o por día. Las cifras
 * de ejecuciones enlazan a la lista con el mismo filtro. El periodo vive en la URL.
 */
export function DashboardMetrics() {
  const [params, setParams] = useSearchParams()
  const period = (PERIODS.find((p) => p.id === params.get('period'))?.id ?? '7d') as MetricsPeriod
  const metrics = useQuery({
    queryKey: ['dashboard', 'metrics', period],
    queryFn: () => api.dashboardMetrics(period, zone()),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  })

  function choose(next: MetricsPeriod) {
    const p = new URLSearchParams(params)
    p.set('period', next)
    setParams(p, { replace: true })
  }

  const data = metrics.data
  return (
    <section className="card dashboard-metrics" aria-labelledby="metrics-title">
      <div className="metrics-head">
        <h2 id="metrics-title">Métricas</h2>
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
      </div>
      <ErrorMessage error={metrics.error} />
      {data && <Tiles data={data} />}
      {data && <RunsChartFigure data={data} />}
    </section>
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
  icon: LucideIcon
  tone: 'live' | 'bad' | 'ok' | 'idle' | 'primary'
  to?: string
  /** Las cuatro cifras principales van más grandes. */
  main?: boolean
}

function Tiles({ data }: { data: RunMetrics }) {
  const tiles: Tile[] = [
    {
      label: 'Activas',
      value: formatNumber(data.active),
      icon: CirclePlay,
      tone: 'live',
      main: true,
      to: runsLink(data.since, ['PENDING', 'RUNNING']),
    },
    {
      label: 'Fallidas',
      value: formatNumber(data.failed),
      icon: CircleX,
      tone: 'bad',
      main: true,
      to: runsLink(data.since, ['FAILED']),
    },
    { label: 'Coste', value: formatCost(data.costUsd), icon: Coins, tone: 'primary', main: true },
    {
      label: 'Duración mediana',
      value:
        data.medianDurationSeconds == null
          ? '—'
          : formatDuration(Math.round(data.medianDurationSeconds * 1000)),
      icon: Timer,
      tone: 'primary',
      main: true,
    },
    {
      label: 'Ejecuciones',
      value: formatNumber(data.total),
      icon: Layers,
      tone: 'idle',
      to: runsLink(data.since),
    },
    {
      label: 'Completadas',
      value: formatNumber(data.succeeded),
      icon: CircleCheck,
      tone: 'ok',
      to: runsLink(data.since, ['SUCCEEDED']),
    },
    {
      label: 'Canceladas',
      value: formatNumber(data.cancelled),
      icon: Square,
      tone: 'idle',
      to: runsLink(data.since, ['CANCELLED']),
    },
    {
      label: 'Tokens (entrada / salida)',
      value: `${formatNumber(data.inputTokens)} / ${formatNumber(data.outputTokens)}`,
      icon: Type,
      tone: 'idle',
    },
  ]
  return (
    <dl className="kpis">
      {tiles.map((t) => (
        <div key={t.label} className={cn('kpi', `kpi-${t.tone}`, t.main && 'kpi-main')}>
          <span className="kpi-icon" aria-hidden="true">
            <t.icon size={t.main ? 24 : 18} strokeWidth={2.5} />
          </span>
          <dt>{t.label}</dt>
          <dd>{t.to ? <Link to={t.to}>{t.value}</Link> : t.value}</dd>
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
    <figure className="runs-chart">
      <figcaption>
        Ejecuciones por {unit === 'hour' ? 'hora' : 'día'}
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
