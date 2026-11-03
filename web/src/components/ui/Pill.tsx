import type { ReactNode } from 'react'
import type { StatusTone } from '../../format'
import { cn } from '../../lib/cn'
import { Companion } from '../companion/Companion'
import type { CompanionState } from '../companion/state'
import { Icon } from '../icons/Icon'
import { hasOwnDrawing } from '../icons/variant'
import { useIconPack } from '../icons/pack'
import type { IconName } from '../icons/registry'
import { TONE_BADGE, TONE_ICON } from './statusIcons'

/**
 * Estado como píldora: color, icono y texto, nunca solo color. El tono `active` late para
 * indicar que algo está vivo. Con los iconos ilustrados, la insignia hace de círculo de color y,
 * con `live`, repite su gesto en vez de latir.
 */
export function Pill({
  tone,
  icon = TONE_ICON[tone],
  face,
  faceSize = 40,
  live = false,
  className,
  children,
}: {
  tone: StatusTone
  icon?: IconName
  /** Expresión de Skyny que sustituye al icono (boceto «Skyny en los bullets»). */
  face?: CompanionState
  faceSize?: number
  /** Estado en vivo de la ejecución abierta: su insignia ilustrada se mueve. */
  live?: boolean
  className?: string
  children: ReactNode
}) {
  // Sin dibujo propio en el paquete elegido, se queda el icono de Lucide sobre su círculo de color.
  const pack = useIconPack()
  const ilustrados = pack !== 'lucide' && hasOwnDrawing(pack, icon)
  if (face)
    return (
      <span className={cn('pill', 'pill-skyny', tone !== 'neutral' && `pill-${tone}`, className)}>
        <span className="pill-face" aria-hidden="true">
          <Companion state={face} size={faceSize} still={!live} season={null} />
        </span>
        {children}
      </span>
    )
  return (
    <span className={cn('pill', tone !== 'neutral' && `pill-${tone}`, className)}>
      <span className={cn('pill-icon', ilustrados && 'pill-icon-ilustrado')} aria-hidden="true">
        <Icon
          name={icon}
          size={ilustrados ? 22 : 13}
          strokeWidth={3}
          tone={TONE_BADGE[tone]}
          loop={live}
        />
      </span>
      {children}
    </span>
  )
}
