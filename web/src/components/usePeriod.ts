import { useSearchParams } from 'react-router'
import type { MetricsPeriod } from '../api'

export const PERIODS: { id: MetricsPeriod; label: string }[] = [
  { id: '24h', label: '24 horas' },
  { id: '7d', label: '7 días' },
  { id: '30d', label: '30 días' },
]

/** Periodo de las métricas, guardado en la URL (`?period=`); 7 días si no hay ninguno. */
export function usePeriod(): [MetricsPeriod, (next: MetricsPeriod) => void] {
  const [params, setParams] = useSearchParams()
  const period = (PERIODS.find((p) => p.id === params.get('period'))?.id ?? '7d') as MetricsPeriod
  function choose(next: MetricsPeriod) {
    const p = new URLSearchParams(params)
    p.set('period', next)
    setParams(p, { replace: true })
  }
  return [period, choose]
}
