import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { MetricsBucket, RunMetrics } from '../api'
import { formatCost } from '../format'
import { bucketLabel, bucketTitle } from './metricsFormat'

interface Row extends MetricsBucket {
  label: string
  title: string
  other: number
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
    <ResponsiveContainer width="100%" height={200}>
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
        <Bar dataKey="succeeded" stackId="runs" className="bar-ok" maxBarSize={28} />
        <Bar dataKey="failed" stackId="runs" className="bar-bad" maxBarSize={28} />
        <Bar dataKey="cancelled" stackId="runs" className="bar-idle" maxBarSize={28} />
        <Bar
          dataKey="other"
          stackId="runs"
          className="bar-live"
          maxBarSize={28}
          radius={[6, 6, 0, 0]}
        />
      </BarChart>
    </ResponsiveContainer>
  )
}
