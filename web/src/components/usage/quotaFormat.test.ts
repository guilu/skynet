import { describe, expect, it } from 'vitest'
import type { QuotaShare, QuotaWarning } from '../../api'
import {
  formatAgo,
  formatPercent,
  formatReset,
  formatSpan,
  freshness,
  headlineShare,
  quotaAlerts,
  quotaStatus,
  renewed,
  sharesByProvider,
  sharesLine,
} from './quotaFormat'

const now = Date.parse('2026-10-05T09:05:00Z')
const share = (
  provider: string,
  window: QuotaShare['window'],
  value: number,
  method: QuotaShare['method'] = 'measured',
): QuotaShare => ({ provider, bucket: 'default', window, share: value, method })

describe('quotaFormat', () => {
  it('da el % con más decimales cuanto más pequeño es', () => {
    expect(formatPercent(0.62)).toBe('62 %')
    expect(formatPercent(0.034)).toBe('3,4 %')
    expect(formatPercent(0.0012)).toBe('0,12 %')
    expect(formatPercent(0)).toBe('0 %')
  })

  it('el estado de un CLI sale de su ventana más gastada', () => {
    expect(quotaStatus(0.5)).toEqual({ tone: 'ok', label: 'Disponible' })
    expect(quotaStatus(0.75)).toEqual({ tone: 'warn', label: 'Justa' })
    expect(quotaStatus(0.9)).toEqual({ tone: 'bad', label: 'Casi agotada' })
  })

  it('dice cuándo se renueva y de cuándo es el dato', () => {
    expect(formatSpan(4 * 3_600_000 + 25 * 60_000)).toBe('4 h 25 min')
    expect(formatSpan(30_000)).toBe('menos de 1 min')
    expect(formatReset('2026-10-05T11:30:00Z', now)).toMatch(/^a las \d\d:30 \(en 2 h 25 min\)$/)
    expect(formatReset('2026-10-09T09:00:00Z', now)).toMatch(/^el vie 9, \d\d:00$/)
    expect(formatReset(null, now)).toBe('sin fecha de renovación')
    expect(renewed({ resetsAt: '2026-10-05T09:00:00Z' }, now)).toBe(true)
    expect(formatAgo('2026-10-05T09:01:00Z', now)).toBe('hace 4 min')
    expect(freshness({ source: 'invocation', observedAt: '2026-10-05T09:01:00Z' }, now)).toBe(
      'Dato de hace 4 min, de la última ejecución',
    )
    expect(freshness({ source: 'poll', observedAt: '2026-10-05T09:04:30Z' }, now)).toBe(
      'Consultado hace un momento',
    )
  })

  it('agrupa las partes por CLI, del que más ha gastado al que menos', () => {
    const shares = [
      share('codex', 'five_hour', 0.03),
      share('codex', 'seven_day', 0.01),
      share('claude-code', 'five_hour', 0.14, 'prorated'),
      share('claude-code', 'seven_day', 0.04, 'prorated'),
    ]
    const byProvider = sharesByProvider(shares)
    expect(byProvider.map((p) => [p.provider, p.measured])).toEqual([
      ['claude-code', false],
      ['codex', true],
    ])
    expect(byProvider.map((p) => sharesLine(p))).toEqual([
      'Claude: 14 % de 5 h · 4 % semanal',
      'Codex: 3 % de 5 h · 1 % semanal',
    ])
    expect(headlineShare(shares)).toEqual({ value: '14 %', suffix: 'de 5 h' })
    expect(headlineShare([share('codex', 'seven_day', 0.02)])).toEqual({
      value: '2 %',
      suffix: 'semanal',
    })
    expect(headlineShare([])).toBeNull()
  })

  it('los avisos llevan a la página Uso y, con varios runners, dicen de cuál son', () => {
    const warning: QuotaWarning = {
      runnerId: 'r1',
      runnerName: 'portatil',
      provider: 'codex',
      providerName: 'Codex',
      bucket: 'default',
      label: null,
      window: 'five_hour',
      usedFraction: 0.96,
      pace: 0.4,
      resetsAt: '2026-10-05T11:30:00Z',
      reason: 'limit',
    }
    const [limit] = quotaAlerts([warning], now)
    expect(limit).toMatchObject({
      to: '/usage',
      tone: 'bad',
      title: 'Codex al 96 % de la ventana de 5 h',
      status: 'Casi agotada',
    })
    expect(limit.detail).toMatch(/^Se renueva a las \d\d:30 \(en 2 h 25 min\)$/)

    const pace = {
      ...warning,
      window: 'seven_day' as const,
      usedFraction: 0.84,
      reason: 'pace' as const,
    }
    const alerts = quotaAlerts([pace, { ...pace, runnerId: 'r2', runnerName: 'servidor' }], now)
    expect(alerts[0]).toMatchObject({
      tone: 'warn',
      title: 'Codex al 84 % de la semana',
      status: 'Por encima del ritmo',
    })
    expect(alerts[0].detail).toMatch(
      /^Va por delante del ritmo \(40 %\)\. Se renueva .* · runner portatil$/,
    )
    expect(alerts[1].detail).toMatch(/runner servidor$/)
  })
})
