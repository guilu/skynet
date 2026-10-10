import { CircleAlert, Clock, Info } from 'lucide-react'
import type { Problem } from '../../api'

const ORDER: Problem['severity'][] = ['ERROR', 'UNSUPPORTED', 'WARNING']

const KIND = {
  ERROR: { icon: CircleAlert, label: 'Error', className: 'problem-error' },
  UNSUPPORTED: { icon: Clock, label: 'Aún no', className: 'problem-unsupported' },
  WARNING: { icon: Info, label: 'Aviso', className: 'problem-warning' },
} as const

/**
 * Problemas de la definición, primero los errores. Cada uno con línea lleva a su sitio en el
 * editor.
 */
export function ProblemList({
  problems,
  onSelect,
}: {
  problems: Problem[]
  onSelect?: (line: number, column: number) => void
}) {
  if (problems.length === 0) {
    return <p className="muted small">No hay nada que corregir.</p>
  }
  const sorted = [...problems].sort((a, b) => ORDER.indexOf(a.severity) - ORDER.indexOf(b.severity))
  return (
    <ul className="problem-list">
      {sorted.map((p, i) => {
        const kind = KIND[p.severity]
        const Icon = kind.icon
        const where =
          p.line != null ? `Línea ${p.line}${p.column != null ? `:${p.column}` : ''}` : null
        const body = (
          <>
            <Icon size={16} strokeWidth={2.5} aria-hidden="true" className="problem-icon" />
            <span className="problem-text">
              <span className="problem-kind">{kind.label}</span>
              {where && <span className="problem-where">{where}</span>}
              <span className="problem-message">{p.message}</span>
            </span>
          </>
        )
        return (
          <li key={`${p.path}-${p.line}-${p.column}-${i}`} className={kind.className}>
            {onSelect && p.line != null ? (
              <button
                type="button"
                className="problem-item"
                onClick={() => onSelect(p.line!, p.column ?? 1)}
              >
                {body}
              </button>
            ) : (
              <div className="problem-item">{body}</div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
