import { Clock } from 'lucide-react'
import type { UsageWindow } from '../../api'
import { providerName } from '../../format'
import { cn } from '../../lib/cn'
import { Pill } from '../ui/Pill'
import {
  formatPercent,
  freshness,
  quotaStatus,
  quotaTone,
  renewed,
  renewsText,
  windowLabel,
} from './quotaFormat'

const LOGOS: Record<string, string> = {
  'claude-code': 'C',
  codex: 'X',
  antigravity: 'A',
}

/**
 * Tarjeta de un CLI en la página Uso: versión y plan, su estado según la ventana más gastada y un
 * medidor por ventana (y por grupo de modelos, como los de agy). Al pie, de cuándo es el dato.
 */
export function ProviderCard({
  provider,
  windows,
  version,
  now,
}: {
  provider: string
  windows: UsageWindow[]
  version: string | null
  now: number
}) {
  if (windows.length === 0) return <EmptyCard provider={provider} version={version} />
  const name = windows[0].providerName
  const plan = windows.find((w) => w.plan)?.plan
  const latest = windows.reduce((a, b) => (a.observedAt > b.observedAt ? a : b))
  const stale = windows.every((w) => w.stale)
  const status = quotaStatus(Math.max(...windows.map((w) => used(w, now))))
  const buckets = [...new Set(windows.map((w) => w.bucket))]
  const grouped = buckets.length > 1 || windows.some((w) => w.label)
  return (
    <section className={cn('card prov', stale && 'prov-stale')} aria-label={name}>
      <div className="prov-head">
        <span className={cn('prov-logo', `prov-logo-${provider}`)} aria-hidden="true">
          {LOGOS[provider] ?? name.charAt(0)}
        </span>
        <div>
          <h3>{name}</h3>
          <small>{[version, plan && `plan ${capitalize(plan)}`].filter(Boolean).join(' · ')}</small>
        </div>
        <Pill tone={stale ? 'neutral' : status.tone}>{status.label}</Pill>
      </div>
      {buckets.map((bucket) => {
        const of = windows
          .filter((w) => w.bucket === bucket)
          .sort((a, b) => a.window.localeCompare(b.window))
        return (
          <div key={bucket} className="prov-group">
            {grouped && <h4 className="prov-group-head">{of[0].label ?? bucket}</h4>}
            {of.map((w) => (
              <Meter
                key={w.window}
                w={w}
                now={now}
                short={grouped}
                label={of[0].label}
                name={name}
              />
            ))}
          </div>
        )
      })}
      <p className="prov-fresh">
        <Clock size={16} strokeWidth={2.5} aria-hidden="true" />
        {freshness(latest, now)}
        {stale && ': puede estar desfasado'}
      </p>
    </section>
  )
}

/** Un CLI del runner del que aún no hay ninguna muestra de cuota. */
function EmptyCard({ provider, version }: { provider: string; version: string | null }) {
  const name = providerName(provider)
  return (
    <section className="card prov prov-stale" aria-label={name}>
      <div className="prov-head">
        <span className={cn('prov-logo', `prov-logo-${provider}`)} aria-hidden="true">
          {LOGOS[provider] ?? name.charAt(0)}
        </span>
        <div>
          <h3>{name}</h3>
          {version && <small>{version}</small>}
        </div>
        <Pill tone="neutral">Sin datos</Pill>
      </div>
      <p className="prov-note">
        {provider === 'claude-code'
          ? 'Claude Code solo informa de su cuota mientras ejecuta un agente: sale en cuanto corra el próximo.'
          : 'Aún no ha llegado ninguna consulta de su cuota. Pulsa «Actualizar» para pedirla ahora.'}
      </p>
    </section>
  )
}

/** Lo gastado de la ventana, 0 si ya se ha renovado después de la muestra. */
const used = (w: UsageWindow, now: number) => (renewed(w, now) ? 0 : w.usedFraction)

/** Un medidor: lo de Skynet, lo de fuera rayado y la marca del ritmo uniforme. */
function Meter({
  w,
  now,
  short,
  label,
  name,
}: {
  w: UsageWindow
  now: number
  short: boolean
  label: string | null
  name: string
}) {
  const isRenewed = renewed(w, now)
  const total = used(w, now)
  const skynet = isRenewed ? 0 : Math.min(w.skynet, total)
  const other = Math.max(0, total - skynet)
  const title = short ? (w.window === 'five_hour' ? '5 h' : 'Semana') : windowLabel(w.window)
  const tone = quotaTone(total)
  const segments = [
    skynet > 0 && { cls: 'sk', value: skynet, title: `Skynet: ${formatPercent(skynet)}` },
    other > 0 && { cls: 'ot', value: other, title: `Fuera de Skynet: ${formatPercent(other)}` },
  ].filter((s) => s !== false)
  return (
    <div className="win">
      <div className="win-top">
        <b>{title}</b>
        <span className={cn('win-val', tone !== 'ok' && `win-${tone}`)}>
          {formatPercent(total)}
        </span>
      </div>
      <div
        className="meter"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(total * 100)}
        aria-label={`${name}${label ? ` (${label})` : ''}, ${windowLabel(w.window).toLowerCase()}`}
        aria-valuetext={`${formatPercent(total)} usado: Skynet ${formatPercent(skynet)}, fuera ${formatPercent(other)}`}
      >
        {segments.map((s) => (
          <i
            key={s.cls}
            className={cn(s.cls, segments.length === 1 && 'full')}
            style={{ width: `${s.value * 100}%` }}
            title={s.title}
          />
        ))}
        {!isRenewed && w.pace != null && (
          <span
            className="pace"
            style={{ left: `calc(${w.pace * 100}% - 1px)` }}
            title={`Ritmo uniforme: ${formatPercent(w.pace)}`}
          />
        )}
      </div>
      <div className="win-sub">
        <span>
          {isRenewed ? 'Se ha renovado; falta un dato nuevo' : renewsText(w.resetsAt, now)}
        </span>
        <span>
          <span className="win-sk">Skynet {formatPercent(skynet)}</span> · fuera{' '}
          {formatPercent(other)}
        </span>
      </div>
    </div>
  )
}

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)
