import type { IconName } from '../icons/registry'
import type { StreamState } from '../../useEventStream'

/** Cómo se enseña el estado de la conexión en vivo: texto, tono de la píldora e icono. */
export const STREAM: Record<
  StreamState,
  { label: string; tone: 'active' | 'warn' | 'neutral'; icon: IconName }
> = {
  connecting: { label: 'Conectando…', tone: 'neutral', icon: 'reload' },
  open: { label: 'En vivo', tone: 'active', icon: 'live' },
  reconnecting: { label: 'Reconectando…', tone: 'warn', icon: 'reload' },
}
