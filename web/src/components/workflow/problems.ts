import type { Problem } from '../../api'

/** Resumen de una lista de problemas: «2 errores · 1 aún no se ejecuta». */
export function problemSummary(problems: Problem[]): string {
  const count = (s: Problem['severity']) => problems.filter((p) => p.severity === s).length
  const parts = [
    [count('ERROR'), 'error', 'errores'],
    [count('UNSUPPORTED'), 'aún no se ejecuta', 'aún no se ejecutan'],
    [count('WARNING'), 'aviso', 'avisos'],
  ] as const
  const text = parts
    .filter(([n]) => n > 0)
    .map(([n, one, many]) => `${n} ${n === 1 ? one : many}`)
    .join(' · ')
  return text || 'Sin problemas'
}
