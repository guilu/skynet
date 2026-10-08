import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import type { StatusTone } from '../../format'
import { cn } from '../../lib/cn'
import { TONE_ICON } from './statusIcons'

/**
 * Estado como píldora: color, icono y texto, nunca solo color. El tono `active` late para
 * indicar que algo está vivo.
 */
export function Pill({
  tone,
  icon: Icon = TONE_ICON[tone],
  className,
  children,
}: {
  tone: StatusTone
  icon?: LucideIcon
  className?: string
  children: ReactNode
}) {
  return (
    <span className={cn('pill', tone !== 'neutral' && `pill-${tone}`, className)}>
      <span className="pill-icon" aria-hidden="true">
        <Icon size={13} strokeWidth={3} />
      </span>
      {children}
    </span>
  )
}
