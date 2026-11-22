import { useCallback, useRef, useState } from 'react'
import type { StoredEvent } from '../../api'
import { pulseFor, throttlePulse, type PulseType } from './pulse'

/**
 * La reacción al vivo que toca ahora y la función que le pasa cada evento que llega
 * (`pulseFor`), recortando las ráfagas (`throttlePulse`).
 */
export function usePulse(): [
  { type: PulseType; key: number } | null,
  (event: Pick<StoredEvent, 'type' | 'payload'>) => void,
] {
  const [pulse, setPulse] = useState<{ type: PulseType; key: number } | null>(null)
  const last = useRef<number | null>(null)
  const push = useCallback((event: Pick<StoredEvent, 'type' | 'payload'>) => {
    const type = pulseFor(event)
    if (!type) return
    const at = throttlePulse(last.current, performance.now())
    if (at === null) return
    last.current = at
    setPulse((prev) => ({ type, key: (prev?.key ?? 0) + 1 }))
  }, [])
  return [pulse, push]
}
