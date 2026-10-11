import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { MetricsBucket, RunMetrics } from '../api'
import { formatCost } from '../format'
import { bucketLabel, bucketTitle } from './metricsFormat'

const SERIES = ['succeeded', 'failed', 'cancelled', 'other'] as const
const SERIES_TONE = { succeeded: 'ok', failed: 'bad', cancelled: 'idle', other: 'live' } as const

interface Row extends MetricsBucket {
  label: string
  title: string
  other: number
}

/** Hueco entre los segmentos de una misma barra. */
const GAP = 4

/**
 * Cada segmento de la barra es una pastilla con las esquinas redondeadas; se recorta por arriba
 * para dejar un hueco con el segmento siguiente, salvo si es tan bajo que desaparecería.
 */
function Segment({
  x = 0,
  y = 0,
  width = 0,
  height = 0,
  className,
}: {
  x?: number
  y?: number
  width?: number
  height?: number
  className?: string
}) {
  if (height <= 0 || width <= 0) return null
  const h = Math.max(height - GAP, Math.min(height, 6))
  const top = y + (height - h)
  const r = Math.min(width / 2.6, 12, h / 2)
  return <rect x={x} y={top} width={width} height={h} rx={r} ry={r} className={className} />
}

/**
 * Ejecuciones por tramo en barras apiladas por resultado (completadas, fallidas, canceladas y el
 * resto, aún activas). Se carga aparte para que recharts solo se descargue en el dashboard.
 */
export default function RunsChart({ data }: { data: RunMetrics }) {
  const rows: Row[] = data.buckets.map((b) => ({
    ...b,
    label: bucketLabel(b, data.bucket),
    title: bucketTitle(b, data.bucket),
    other: Math.max(0, b.total - b.succeeded - b.failed - b.cancelled),
  }))
  return (
    <ResponsiveContainer width="100%" height={240}>
      <BarChart
        data={rows}
        margin={{ top: 8, right: 8, bottom: 0, left: -16 }}
        accessibilityLayer={false}
      >
        <CartesianGrid vertical={false} className="chart-grid" strokeDasharray="4 6" />
        <XAxis
          dataKey="label"
          tickLine={false}
          axisLine={false}
          className="chart-axis"
          minTickGap={12}
        />
        <YAxis allowDecimals={false} tickLine={false} axisLine={false} className="chart-axis" />
        <Tooltip
          cursor={{ className: 'chart-cursor' }}
          isAnimationActive={false}
          content={({ active, payload }) => {
            const row = payload?.[0]?.payload as Row | undefined
            if (!active || !row) return null
            return (
              <div className="chart-tooltip">
                <strong>{row.title}</strong>
                <span>
                  {row.total} {row.total === 1 ? 'ejecución' : 'ejecuciones'}
                </span>
                <span className="muted">
                  {row.succeeded} completadas · {row.failed} fallidas · {row.cancelled} canceladas
                </span>
                <span className="muted">{formatCost(row.costUsd)}</span>
              </div>
            )
          }}
        />
        {SERIES.map((key) => (
          <Bar
            key={key}
            dataKey={key}
            stackId="runs"
            className={`bar-${SERIES_TONE[key]}`}
            maxBarSize={44}
            shape={<Segment />}
            isAnimationActive={false}
          />
        ))}
      </BarChart>
    </ResponsiveContainer>
  )
}
