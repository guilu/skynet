import type { Problem } from '../../api'

/**
 * Los arreglos en un clic (WF-F): cuando el control plane sugiere otro nombre («No hay ninguna
 * fase `implementa`; ¿quisiste decir `implementar`?»), se cambia en su línea el que está mal por
 * el sugerido. No hace falta saber de qué campo es: el mensaje dice el valor y la sugerencia.
 */
const SUGGESTION = /`([^`]+)`.*; ¿quisiste decir `([^`]+)`\?$/

export interface QuickFix {
  from: string
  to: string
}

/** El cambio que propone el problema, si propone alguno y se sabe en qué línea está. */
export function quickFixOf(problem: Problem): QuickFix | null {
  const match = SUGGESTION.exec(problem.message)
  if (!match || problem.line == null) return null
  return { from: match[1], to: match[2] }
}

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * El texto con el arreglo hecho: en la línea del problema, desde su columna, la primera vez que
 * aparece el valor como palabra entera. Null si ya no está (el texto cambió desde la validación).
 */
export function applyQuickFix(text: string, problem: Problem): string | null {
  const fix = quickFixOf(problem)
  if (!fix || problem.line == null) return null
  const lines = text.split('\n')
  const line = lines[problem.line - 1]
  if (line == null) return null
  const word = new RegExp(`(?<![\\w-])${escape(fix.from)}(?![\\w-])`)
  const start = Math.max(0, (problem.column ?? 1) - 1)
  const at = word.exec(line.slice(start)) ? start : 0
  const match = word.exec(line.slice(at))
  if (!match) return null
  const index = at + match.index
  lines[problem.line - 1] = line.slice(0, index) + fix.to + line.slice(index + fix.from.length)
  return lines.join('\n')
}
