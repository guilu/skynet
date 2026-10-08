import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, type MetricsBucket, type MetricsPeriod, type RunMetrics } from '../api'
import { formatCost, formatDuration, formatNumber } from '../format'
import { ErrorMessage } from './ErrorMessage'

const PERIODS: { id: MetricsPeriod; label: string }[] = [
  { id: '24h', label: '24 horas' },
  { id: '7d', label: '7 días' },
  { id: '30d', label: '30 días' },
]

/** Zona del navegador: los tramos del gráfico siguen su calendario. */
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

/**
 * Métricas de las ejecuciones creadas en el periodo elegido (24 h, 7 o 30 días): cuántas hay por
 * estado, su duración mediana, tokens y coste, y las ejecuciones por hora o por día. Cada cifra
 * enlaza a la lista de ejecuciones con el mismo filtro. El periodo vive en la URL.
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
      {data && <RunsChart data={data} />}
    </section>
  )
}

function runsLink(since: string, status: string[] = []) {
  const p = new URLSearchParams()
  status.forEach((s) => p.append('status', s))
  p.set('since', since)
  return `/runs?${p}`
}

function Tiles({ data }: { data: RunMetrics }) {
  const tiles: { label: string; value: string; to?: string }[] = [
    { label: 'Ejecuciones', value: formatNumber(data.total), to: runsLink(data.since) },
    {
      label: 'Completadas',
      value: formatNumber(data.succeeded),
      to: runsLink(data.since, ['SUCCEEDED']),
    },
    { label: 'Fallidas', value: formatNumber(data.failed), to: runsLink(data.since, ['FAILED']) },
    {
      label: 'Canceladas',
      value: formatNumber(data.cancelled),
      to: runsLink(data.since, ['CANCELLED']),
    },
    {
      label: 'Activas',
      value: formatNumber(data.active),
      to: runsLink(data.since, ['PENDING', 'RUNNING']),
    },
    {
      label: 'Duración mediana',
      value:
        data.medianDurationSeconds == null
          ? '—'
          : formatDuration(Math.round(data.medianDurationSeconds * 1000)),
    },
    {
      label: 'Tokens (entrada / salida)',
      value: `${formatNumber(data.inputTokens)} / ${formatNumber(data.outputTokens)}`,
    },
    { label: 'Coste', value: formatCost(data.costUsd) },
  ]
  return (
    <dl className="stat-tiles">
      {tiles.map((t) => (
        <div key={t.label} className="stat-tile">
          <dt>{t.label}</dt>
          <dd>{t.to ? <Link to={t.to}>{t.value}</Link> : t.value}</dd>
        </div>
      ))}
    </dl>
  )
}

const hourLabel = new Intl.DateTimeFormat('es-ES', { hour: '2-digit', minute: '2-digit' })
const dayLabel = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short' })
const longLabel = new Intl.DateTimeFormat('es-ES', { dateStyle: 'medium', timeStyle: 'short' })

function bucketLabel(bucket: MetricsBucket, unit: RunMetrics['bucket']) {
  const date = new Date(bucket.start)
  return unit === 'hour' ? hourLabel.format(date) : dayLabel.format(date)
}

function bucketTitle(bucket: MetricsBucket, unit: RunMetrics['bucket']) {
  const date = new Date(bucket.start)
  return unit === 'hour' ? longLabel.format(date) : dayLabel.format(date)
}

const WIDTH = 640
const HEIGHT = 160
const PAD = { top: 8, right: 8, bottom: 22, left: 28 }

/**
 * Ejecuciones por tramo: barras finas sobre una cuadrícula discreta, con el detalle del tramo al
 * pasar por encima o al enfocarlo, y la misma información en una tabla.
 */
function RunsChart({ data }: { data: RunMetrics }) {
  const [hover, setHover] = useState<number | null>(null)
  const buckets = data.buckets
  if (buckets.length === 0) return null
  const max = Math.max(1, ...buckets.map((b) => b.total))
  const ticks = niceTicks(max)
  const top = ticks[ticks.length - 1]
  const plotW = WIDTH - PAD.left - PAD.right
  const plotH = HEIGHT - PAD.top - PAD.bottom
  const slot = plotW / buckets.length
  const barW = Math.max(2, Math.min(24, slot - 2))
  const y = (v: number) => PAD.top + plotH - (v / top) * plotH
  // Unas pocas etiquetas en el eje: no más de ocho.
  const every = Math.ceil(buckets.length / 8)
  const unit = data.bucket
  const current = hover == null ? null : buckets[hover]

  return (
    <figure className="runs-chart">
      <figcaption>Ejecuciones por {unit === 'hour' ? 'hora' : 'día'}</figcaption>
      <div className="chart-frame">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          role="img"
          aria-label={`Ejecuciones por ${unit === 'hour' ? 'hora' : 'día'}: ${data.total} en total, máximo ${max} en un tramo`}
          onMouseLeave={() => setHover(null)}
        >
          {ticks.map((t) => (
            <g key={t} className="chart-grid">
              <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y(t)} y2={y(t)} />
              <text x={PAD.left - 6} y={y(t)} dy="0.32em" textAnchor="end">
                {t}
              </text>
            </g>
          ))}
          {buckets.map((b, i) => {
            const x = PAD.left + i * slot + (slot - barW) / 2
            const h = (b.total / top) * plotH
            return (
              <g key={b.start}>
                {b.total > 0 && (
                  <path
                    className={`chart-bar${hover === i ? ' is-hover' : ''}`}
                    d={roundedTop(x, y(b.total), barW, h, Math.min(4, barW / 2, h))}
                  />
                )}
                {i % every === 0 && (
                  <text
                    className="chart-axis"
                    x={PAD.left + i * slot + slot / 2}
                    y={HEIGHT - 6}
                    textAnchor="middle"
                  >
                    {bucketLabel(b, unit)}
                  </text>
                )}
                {/* Zona de hover: todo el alto del tramo, más grande que la barra. */}
                <rect
                  className="chart-hit"
                  x={PAD.left + i * slot}
                  y={PAD.top}
                  width={slot}
                  height={plotH}
                  onMouseEnter={() => setHover(i)}
                />
              </g>
            )
          })}
          <line
            className="chart-baseline"
            x1={PAD.left}
            x2={WIDTH - PAD.right}
            y1={y(0)}
            y2={y(0)}
          />
        </svg>
        {current && hover != null && (
          <div
            className="chart-tooltip"
            role="status"
            style={tooltipPosition((PAD.left + hover * slot + slot / 2) / WIDTH)}
          >
            <strong>{bucketTitle(current, unit)}</strong>
            <span>
              {current.total} {current.total === 1 ? 'ejecución' : 'ejecuciones'}
            </span>
            <span className="muted">
              {current.succeeded} completadas · {current.failed} fallidas · {current.cancelled}{' '}
              canceladas
            </span>
            <span className="muted">{formatCost(current.costUsd)}</span>
          </div>
        )}
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

/** El tooltip se ancla a la barra y se abre hacia dentro cerca de los bordes del gráfico. */
function tooltipPosition(fraction: number) {
  const left = `${fraction * 100}%`
  if (fraction > 0.7) return { left, transform: 'translateX(calc(-100% - 8px))' }
  if (fraction < 0.3) return { left, transform: 'translateX(8px)' }
  return { left, transform: 'translateX(-50%)' }
}

/** Marcas del eje: 0 y hasta cuatro pasos enteros que cubren `max`. */
function niceTicks(max: number): number[] {
  const step = Math.max(1, Math.ceil(max / 4))
  const ticks = []
  for (let t = 0; t < max + step; t += step) ticks.push(t)
  return ticks
}

/** Barra con las esquinas de arriba redondeadas y la base recta, apoyada en el eje. */
function roundedTop(x: number, y: number, w: number, h: number, r: number) {
  return [
    `M${x},${y + h}`,
    `V${y + r}`,
    `Q${x},${y} ${x + r},${y}`,
    `H${x + w - r}`,
    `Q${x + w},${y} ${x + w},${y + r}`,
    `V${y + h}`,
    'Z',
  ].join(' ')
}
