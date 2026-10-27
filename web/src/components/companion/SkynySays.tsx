import { useEffect } from 'react'
import { cn } from '../../lib/cn'
import { Companion } from './Companion'
import { useGesture, type Gesture } from './gesture'
import type { CompanionState } from './state'

/**
 * Skyny reacciona a algo que acabas de hacer (aprobar, rechazar, publicar…): hace su gesto al
 * aparecer y lo dice en un bocadillo, que se anuncia a los lectores de pantalla (SY-C).
 */
export function SkynySays({
  text,
  gesture,
  state = 'calm',
  className,
}: {
  text: string
  gesture: Gesture
  state?: CompanionState
  className?: string
}) {
  const [current, play] = useGesture()
  useEffect(() => play(gesture), [gesture, text, play])
  return (
    <div className={cn('skyny-says', className)}>
      <Companion state={state} size={56} gesture={current} />
      <p className="peek-bubble" role="status">
        {text}
      </p>
    </div>
  )
}
