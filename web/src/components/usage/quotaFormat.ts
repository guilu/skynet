import { Gauge } from 'lucide-react'
import type { QuotaShare, QuotaWarning, QuotaWindowKind, UsageWindow } from '../../api'
import type { StatusTone } from '../../format'
import type { Alert } from '../AttentionList'

/** Proveedores cuya cuota se ve pero cuyos agentes Skynet aún no lanza. */
export const READ_ONLY_PROVIDERS = ['antigravity']

const SHORT_NAMES: Record<string, string> = {
  'claude-code': 'Claude',
  codex: 'Codex',
  gemini: 'Gemini',
  antigravity: 'agy',
}

/** Nombre corto de un CLI para los selectores y las líneas compactas: «Claude», «agy». */
export const shortProvider = (id: string) => SHORT_NAMES[id] ?? id

/** «Ventana de 5 h» o «Semana». */
export const windowLabel = (w: QuotaWindowKind) => (w === 'five_hour' ? 'Ventana de 5 h' : 'Semana')

/** Lo que sigue a una cifra: «14 % de 5 h», «4 % semanal». */
export const windowSuffix = (w: QuotaWindowKind) => (w === 'five_hour' ? 'de 5 h' : 'semanal')

/**
 * Una parte de la ventana (de 0 a 1) en %: sin decimales desde el 10 %, con uno desde el 1 % y con
 * dos por debajo, porque lo de un agente suele ser poco.
 */
export function formatPercent(fraction: number): string {
  const v = fraction * 100
  const digits = v >= 10 ? 0 : v >= 1 ? 1 : 2
  return `${v.toLocaleString('es-ES', { maximumFractionDigits: digits })} %`
}

/** Tono de una ventana: mal desde el 90 %, justa desde el 75 %. */
export function quotaTone(used: number): StatusTone {
  if (used >= 0.9) return 'bad'
  if (used >= 0.75) return 'warn'
  return 'ok'
}

/** Píldora de un CLI según su ventana más gastada. */
export function quotaStatus(used: number): { tone: StatusTone; label: string } {
  const tone = quotaTone(used)
  return {
    tone,
    label: tone === 'bad' ? 'Casi agotada' : tone === 'warn' ? 'Justa' : 'Disponible',
  }
}

/** Si la ventana ya se ha renovado después de la muestra (lo gastado vuelve a 0). */
export const renewed = (w: Pick<UsageWindow, 'resetsAt'>, now: number) =>
  w.resetsAt != null && Date.parse(w.resetsAt) <= now

const clock = new Intl.DateTimeFormat('es-ES', { hour: '2-digit', minute: '2-digit' })
const dayClock = new Intl.DateTimeFormat('es-ES', {
  weekday: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

/** «4 h 25 min», «12 min», «menos de 1 min». */
export function formatSpan(ms: number): string {
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 1) return 'menos de 1 min'
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m} min`
  return m === 0 ? `${h} h` : `${h} h ${m} min`
}

/**
 * Cuándo se renueva: «a las 16:40 (en 4 h 25 min)» si es en menos de un día, «el jue 22, 09:00» si
 * no.
 */
export function formatReset(resetsAt: string | null, now: number): string {
  if (resetsAt == null) return 'sin fecha de renovación'
  const at = Date.parse(resetsAt)
  if (at <= now) return 'ya se ha renovado'
  if (at - now < 24 * 3_600_000) return `a las ${clock.format(at)} (en ${formatSpan(at - now)})`
  return `el ${dayClock.format(at)}`
}

/** «Se renueva a las 16:40 (en 4 h 25 min)» o «Sin fecha de renovación». */
export const renewsText = (resetsAt: string | null, now: number) =>
  resetsAt == null ? 'Sin fecha de renovación' : `Se renueva ${formatReset(resetsAt, now)}`

/** «hace 4 min», «hace 2 h», «hace un momento». */
export function formatAgo(iso: string, now: number): string {
  const ms = now - Date.parse(iso)
  if (ms < 60_000) return 'hace un momento'
  return `hace ${formatSpan(ms)}`
}

/** De dónde sale el dato de un CLI y cuándo: tras una ejecución o de una consulta del runner. */
export function freshness(w: Pick<UsageWindow, 'source' | 'observedAt'>, now: number): string {
  return w.source === 'invocation'
    ? `Dato de ${formatAgo(w.observedAt, now)}, de la última ejecución`
    : `Consultado ${formatAgo(w.observedAt, now)}`
}

/** Nombre del CLI con su grupo de modelos si tiene varios: «Antigravity CLI (Modelos Gemini)». */
const fullName = (w: Pick<QuotaWarning, 'providerName' | 'label'>) =>
  w.label ? `${w.providerName} (${w.label})` : w.providerName

/** Título y detalle de un aviso de cuota, para el Dashboard y la página Uso. */
export function warningText(w: QuotaWarning, now: number, withRunner: boolean) {
  const where = w.window === 'five_hour' ? 'de la ventana de 5 h' : 'de la semana'
  const reset = renewsText(w.resetsAt, now)
  const runner = withRunner ? ` · runner ${w.runnerName}` : ''
  return w.reason === 'limit'
    ? {
        title: `${fullName(w)} al ${formatPercent(w.usedFraction)} ${where}`,
        detail: `${reset}${runner}`,
        status: 'Casi agotada',
        tone: 'bad' as const,
      }
    : {
        title: `${fullName(w)} al ${formatPercent(w.usedFraction)} ${where}`,
        detail: `Va por delante del ritmo (${formatPercent(w.pace ?? 0)}). ${reset}${runner}`,
        status: 'Por encima del ritmo',
        tone: 'warn' as const,
      }
}

/** Avisos de cuota: llevan a la página Uso. Con varios runners, dicen de cuál es. */
export function quotaAlerts(warnings: QuotaWarning[], now: number): Alert[] {
  const runners = new Set(warnings.map((w) => w.runnerId)).size
  return warnings.map((w) => ({
    key: `quota-${w.runnerId}-${w.provider}-${w.bucket}-${w.window}`,
    to: '/usage',
    icon: Gauge,
    ...warningText(w, now, runners > 1),
  }))
}

/** Las partes de un CLI, de la ventana de 5 h a la semanal. */
export interface ProviderShares {
  provider: string
  fiveHour: number | null
  sevenDay: number | null
  measured: boolean
}

/**
 * Agrupa las partes por CLI (sumando los grupos de modelos), del que más ventana de 5 h ha gastado
 * al que menos.
 */
export function sharesByProvider(shares: QuotaShare[]): ProviderShares[] {
  const map = new Map<string, ProviderShares>()
  for (const s of shares) {
    const p = map.get(s.provider) ?? {
      provider: s.provider,
      fiveHour: null,
      sevenDay: null,
      measured: true,
    }
    if (s.window === 'five_hour') p.fiveHour = (p.fiveHour ?? 0) + s.share
    else p.sevenDay = (p.sevenDay ?? 0) + s.share
    if (s.method !== 'measured') p.measured = false
    map.set(s.provider, p)
  }
  return [...map.values()].sort(
    (a, b) => (b.fiveHour ?? 0) - (a.fiveHour ?? 0) || (b.sevenDay ?? 0) - (a.sevenDay ?? 0),
  )
}

/** «Claude: 14 % de 5 h · 4 % semanal». */
export function sharesLine(p: ProviderShares, name = shortProvider(p.provider)): string {
  const parts = [
    p.fiveHour != null && `${formatPercent(p.fiveHour)} de 5 h`,
    p.sevenDay != null && `${formatPercent(p.sevenDay)} semanal`,
  ].filter(Boolean)
  return `${name}: ${parts.join(' · ')}`
}

/**
 * La cifra principal de un conjunto de partes: la ventana de 5 h del CLI que más ha gastado o, si
 * no hay, su semana. `null` si no hay ninguna.
 */
export function headlineShare(shares: QuotaShare[]): { value: string; suffix: string } | null {
  const top = sharesByProvider(shares)[0]
  if (!top) return null
  return top.fiveHour != null
    ? { value: formatPercent(top.fiveHour), suffix: 'de 5 h' }
    : { value: formatPercent(top.sevenDay ?? 0), suffix: 'semanal' }
}
