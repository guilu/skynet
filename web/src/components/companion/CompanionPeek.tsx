import { cn } from '../../lib/cn'
import { Companion } from './Companion'
import type { CompanionState } from './state'

/**
 * La mascota que asoma en una cabecera con su bocadillo. Es decorativa: el estado lo dice el
 * texto del bocadillo.
 */
export function CompanionPeek({
  state,
  text,
  size = 92,
  className,
}: {
  state: CompanionState
  text: string
  size?: number
  className?: string
}) {
  return (
    <div className={cn('companion-peek', className)}>
      <Companion state={state} size={size} />
      <p className="peek-bubble">{text}</p>
    </div>
  )
}
