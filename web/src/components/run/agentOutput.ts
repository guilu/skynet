/** La salida estructurada de un agente en una línea: «hasBlockingFindings: sí · summary: …». */
export function outputSummary(output: unknown): string {
  if (output == null || typeof output !== 'object' || Array.isArray(output)) return ''
  return Object.entries(output as Record<string, unknown>)
    .map(([field, value]) => `${field}: ${valueText(value)}`)
    .join(' · ')
}

function valueText(value: unknown): string {
  if (value === true) return 'sí'
  if (value === false) return 'no'
  if (value == null) return '—'
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') return value.length > 60 ? `${value.slice(0, 59)}…` : value
  return Array.isArray(value) ? `${value.length} elementos` : '{…}'
}
