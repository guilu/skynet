import type { LucideIcon } from 'lucide-react'
import { Link } from 'react-router'
import type { StatusTone } from '../format'
import { cn } from '../lib/cn'
import { Pill } from './ui/Pill'

export interface Alert {
  key: string
  to: string
  icon: LucideIcon
  tone: StatusTone
  title: string
  detail: string
  status: string
  /** Iconos de barras (pausa) que se ven mejor rellenos. */
  filled?: boolean
}

/** «Necesita tu atención (n)»: cada aviso enlaza a donde se resuelve. */
export function AttentionList({ alerts, className }: { alerts: Alert[]; className?: string }) {
  if (alerts.length === 0) return null
  return (
    <section className={className} aria-labelledby="attention-title">
      <h2 id="attention-title">Necesita tu atención ({alerts.length})</h2>
      <ul className="attn-list">
        {alerts.map((a) => (
          <li key={a.key}>
            <Link to={a.to} className="attn-item">
              <span className={cn('attn-icon', `attn-${a.tone}`)} aria-hidden="true">
                <a.icon size={24} strokeWidth={2.75} fill={a.filled ? 'currentColor' : 'none'} />
              </span>
              <span className="attn-text">
                <strong>{a.title}</strong>
                <small>{a.detail}</small>
              </span>
              <Pill tone={a.tone} icon={a.filled ? undefined : a.icon}>
                {a.status}
              </Pill>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
