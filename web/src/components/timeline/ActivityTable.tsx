import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { formatStamp } from '../../format'
import { eventLink } from '../run/eventLink'
import { Pill } from '../ui/Pill'
import { ORIGIN_LABELS, type TimelineEntry } from './timeline'

/** Cuántas filas se añaden cada vez que se llega al final. */
export const ACTIVITY_PAGE = 50

const SEVERITY_TONE = { info: null, warn: 'warn', error: 'bad' } as const

/**
 * Tabla de la actividad, lo último arriba. Pinta las primeras `ACTIVITY_PAGE` filas y, al
 * acercarse al final de la página, otras tantas (scroll infinito); el botón del pie hace lo mismo
 * con el teclado. Las filas de una ejecución enlazan con su inspector.
 */
export function ActivityTable({ entries }: { entries: TimelineEntry[] }) {
  const [shown, setShown] = useState(ACTIVITY_PAGE)
  const end = useRef<HTMLDivElement>(null)
  const rows = entries.toReversed().slice(0, shown)
  const more = entries.length > shown

  // Se vuelve a observar tras cada tanda: si el final sigue a la vista (la página aún no se ha
  // llenado), el observador avisa otra vez nada más empezar.
  useEffect(() => {
    const el = end.current
    if (!more || !el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) setShown((n) => n + ACTIVITY_PAGE)
      },
      { rootMargin: '400px 0px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [more, shown])

  if (entries.length === 0) return <p className="muted">Sin eventos que mostrar.</p>

  return (
    <>
      <table className="activity-table">
        <thead>
          <tr>
            <th scope="col">Fecha y hora</th>
            <th scope="col" className="activity-origin">
              Origen
            </th>
            <th scope="col" className="activity-type">
              Tipo
            </th>
            <th scope="col">Descripción</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((entry) => {
            const link = eventLink(entry.events[0])
            const tone = SEVERITY_TONE[entry.severity]
            return (
              <tr key={entry.id}>
                <td className="activity-when">{formatStamp(entry.occurredAt)}</td>
                <td className="activity-origin">{ORIGIN_LABELS[entry.origin]}</td>
                <td className="activity-type">
                  <code title={entry.events[0].type}>{entry.events[0].type}</code>
                </td>
                <td className="activity-what">
                  {tone && (
                    <>
                      <Pill tone={tone}>{entry.severity === 'error' ? 'Error' : 'Aviso'}</Pill>{' '}
                    </>
                  )}
                  {link ? <Link to={link}>{entry.title}</Link> : entry.title}
                  {entry.children.length > 0 && (
                    <span className="muted"> · {entry.events.length} eventos</span>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <div ref={end} className="activity-end">
        {more ? (
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            onClick={() => setShown((n) => n + ACTIVITY_PAGE)}
          >
            Ver {Math.min(ACTIVITY_PAGE, entries.length - shown)} más
          </button>
        ) : (
          <span className="muted">
            {entries.length === 1 ? '1 evento' : `${entries.length} eventos`}
          </span>
        )}
      </div>
    </>
  )
}
