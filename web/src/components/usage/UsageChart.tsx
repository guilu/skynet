import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { UsageHistory } from '../../api'
import { formatPercent } from './quotaFormat'

const HOUR = 3_600_000
const LENGTH = { five_hour: 5 * HOUR, seven_day: 7 * 24 * HOUR }

const clock = new Intl.DateTimeFormat('es-ES', { hour: '2-digit', minute: '2-digit' })
const day = new Intl.DateTimeFormat('es-ES', { weekday: 'short', day: 'numeric' })
const dayClock = new Intl.DateTimeFormat('es-ES', {
  weekday: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

interface Row {
  at: number
  skynet: number
  other: number
}

/** Las muestras como escalones: lo de Skynet y lo de fuera, en puntos de la ventana. */
function chartRows(history: UsageHistory, now: number): Row[] {
  const rows = history.points.map((p) => ({
    at: Date.parse(p.at),
    skynet: p.skynet * 100,
    other: Math.max(0, p.usedFraction - p.skynet) * 100,
  }))
  // El último escalón llega hasta ahora.
  const last = rows.at(-1)
  if (last && now > last.at) rows.push({ ...last, at: now })
  return rows
}

/**
 * Evolución de la ventana actual de un CLI: área apilada en escalera, lo de Skynet abajo y lo de
 * fuera rayado encima, con la línea de «ahora». Se carga aparte, como el gráfico del dashboard.
 */
export default function UsageChart({ history, now }: { history: UsageHistory; now: number }) {
  const start = Date.parse(history.windowStart)
  const end = history.resetsAt ? Date.parse(history.resetsAt) : start + LENGTH[history.window]
  const rows = chartRows(history, Math.min(now, end))
  const week = history.window === 'seven_day'
  const step = week ? 24 * HOUR : HOUR
  const ticks: number[] = []
  for (let t = Math.ceil(start / step) * step; t <= end; t += step) ticks.push(t)
  return (
    <ResponsiveContainer width="100%" height={260}>
      <AreaChart data={rows} margin={{ top: 16, right: 12, bottom: 0, left: 4 }}>
        <defs>
          <pattern
            id="usage-hatch"
            width="6"
            height="6"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <rect width="6" height="6" className="hatch-bg" />
            <rect width="2" height="6" className="hatch-line" />
          </pattern>
        </defs>
        <CartesianGrid vertical={false} className="chart-grid" strokeDasharray="4 6" />
        <XAxis
          dataKey="at"
          type="number"
          scale="time"
          domain={[start, end]}
          ticks={ticks}
          tickFormatter={(t: number) => (week ? day.format(t) : clock.format(t))}
          tickLine={false}
          axisLine={false}
          className="chart-axis"
          minTickGap={16}
        />
        <YAxis
          domain={[0, 100]}
          ticks={[0, 25, 50, 75, 100]}
          tickFormatter={(v: number) => `${v} %`}
          tickLine={false}
          axisLine={false}
          className="chart-axis"
          width={56}
        />
        <Tooltip
          cursor={{ className: 'chart-cursor-line' }}
          isAnimationActive={false}
          content={({ active, payload }) => {
            const row = payload?.[0]?.payload as Row | undefined
            if (!active || !row) return null
            return (
              <div className="chart-tooltip usage-tip">
                <strong>{(week ? dayClock : clock).format(row.at)}</strong>
                <span>
                  Skynet <b>{formatPercent(row.skynet / 100)}</b>
                </span>
                <span>
                  Fuera de Skynet <b>{formatPercent(row.other / 100)}</b>
                </span>
                <span>
                  Total <b>{formatPercent((row.skynet + row.other) / 100)}</b>
                </span>
              </div>
            )
          }}
        />
        <Area
          type="stepAfter"
          dataKey="skynet"
          stackId="u"
          className="area-sk"
          isAnimationActive={false}
        />
        <Area
          type="stepAfter"
          dataKey="other"
          stackId="u"
          className="area-ot"
          fill="url(#usage-hatch)"
          isAnimationActive={false}
        />
        {now < end && (
          <ReferenceLine
            x={now}
            className="chart-now"
            label={{ value: 'ahora', position: 'insideTopRight', className: 'chart-now-label' }}
          />
        )}
      </AreaChart>
    </ResponsiveContainer>
  )
}
