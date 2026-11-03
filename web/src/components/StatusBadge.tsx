import { statusLabel, statusTone } from '../format'
import type { CompanionState } from './companion/state'
import { Pill } from './ui/Pill'
import { statusFace, statusIcon } from './ui/statusIcons'

/**
 * Píldora de un estado. Si el estado tiene una expresión de Skyny, la lleva en vez del icono
 * (boceto «Skyny en los bullets»); `face` la fuerza (p. ej. Revisando en una verificación). Con
 * `live`, Skyny se mueve (ejecución abierta).
 */
export function StatusBadge({
  status,
  live = false,
  face,
}: {
  status: string
  live?: boolean
  face?: CompanionState
}) {
  const tone = statusTone(status)
  return (
    <Pill
      tone={tone}
      icon={statusIcon(status, tone)}
      face={face ?? statusFace(status)}
      live={live && tone === 'active'}
    >
      {statusLabel(status)}
    </Pill>
  )
}
