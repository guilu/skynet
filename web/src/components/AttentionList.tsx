import { Link } from 'react-router'
import type { StatusTone } from '../format'
import { cn } from '../lib/cn'
import { Icon } from './icons/Icon'
import type { IconName } from './icons/registry'
import { TONE_BADGE } from './ui/statusIcons'
import { Pill } from './ui/Pill'

export interface Alert {
  key: string
  to: string
  icon: IconName
  tone: StatusTone
  title: string
  detail: string
  status: string
  /** Iconos de barras (pausa) que se ven mejor rellenos. */
  filled?: boolean
  /** Lo que se hace al abrirlo («Revisar», «Ver intentos»): se ve como botón en vez del estado. */
  action?: string
}

/**
 * «Necesita tu atención (n)»: cada aviso enlaza a donde se resuelve. En Inicio (FS-C) se titula
 * «Necesita tu intervención» y los avisos con acción la muestran como botón.
 */
export function AttentionList({
  alerts,
  className,
  title = 'Necesita tu atención',
}: {
  alerts: Alert[]
  className?: string
  title?: string
}) {
  if (alerts.length === 0) return null
  return (
    <section className={className} aria-labelledby="attention-title">
      <h2 id="attention-title">
        {title} ({alerts.length})
      </h2>
      <ul className="attn-list">
        {alerts.map((a) => (
          <li key={a.key}>
            <Link to={a.to} className="attn-item">
              <span className={cn('attn-icon', `attn-${a.tone}`)} aria-hidden="true">
                <Icon
                  name={a.icon}
                  size={24}
                  strokeWidth={2.75}
                  filled={a.filled}
                  tone={TONE_BADGE[a.tone]}
                />
              </span>
              <span className="attn-text">
                <strong>{a.title}</strong>
                <small>{a.detail}</small>
              </span>
              {a.action ? (
                <span className={cn('btn btn-sm', a.tone !== 'warn' && 'btn-secondary')}>
                  {a.action}
                </span>
              ) : (
                <Pill tone={a.tone} icon={a.filled ? undefined : a.icon}>
                  {a.status}
                </Pill>
              )}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
