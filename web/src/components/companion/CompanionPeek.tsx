import { cn } from '../../lib/cn'
import { Companion } from './Companion'
import { usePoke, type Gesture } from './gesture'
import type { CompanionState } from './state'

/**
 * La mascota que asoma en una cabecera con su bocadillo. Es decorativa: el estado lo dice el
 * texto del bocadillo. Al pulsarla da un saltito y dice una frase (SY-C); los ojos siguen al
 * cursor.
 */
export function CompanionPeek({
  state,
  text,
  size = 92,
  gesture,
  sweat,
  className,
}: {
  state: CompanionState
  text: string
  size?: number
  /** Un gesto que viene de fuera (el bostezo al despertarse). */
  gesture?: Gesture | null
  sweat?: boolean
  className?: string
}) {
  const poked = usePoke()
  return (
    <div className={cn('companion-peek', className)}>
      <button
        type="button"
        className="skyny-poke"
        aria-label="Hablar con Skyny"
        onClick={poked.poke}
      >
        <Companion
          state={state}
          size={size}
          gesture={poked.gesture ?? gesture}
          sweat={sweat}
          look
        />
      </button>
      <p className="peek-bubble" aria-live="polite">
        {poked.quip ?? text}
      </p>
    </div>
  )
}
