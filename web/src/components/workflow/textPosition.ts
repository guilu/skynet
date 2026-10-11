/** Posición en el texto de la línea y la columna (desde 1). */
export function offsetOf(text: string, line: number, column: number): number {
  let offset = 0
  for (let i = 1; i < line; i++) {
    const next = text.indexOf('\n', offset)
    if (next < 0) return text.length
    offset = next + 1
  }
  const end = text.indexOf('\n', offset)
  const lineEnd = end < 0 ? text.length : end
  return Math.min(offset + Math.max(column, 1) - 1, lineEnd)
}
