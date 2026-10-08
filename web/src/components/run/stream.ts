import { Radio, RotateCw, type LucideIcon } from 'lucide-react'
import type { StreamState } from '../../useEventStream'

/** Cómo se enseña el estado de la conexión en vivo: texto, tono de la píldora e icono. */
export const STREAM: Record<
  StreamState,
  { label: string; tone: 'active' | 'warn' | 'neutral'; icon: LucideIcon }
> = {
  connecting: { label: 'Conectando…', tone: 'neutral', icon: RotateCw },
  open: { label: 'En vivo', tone: 'active', icon: Radio },
  reconnecting: { label: 'Reconectando…', tone: 'warn', icon: RotateCw },
}
