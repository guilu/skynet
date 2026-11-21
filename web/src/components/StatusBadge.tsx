import { statusLabel, statusTone } from '../format'
import { Pill } from './ui/Pill'
import { statusIcon } from './ui/statusIcons'

/** Píldora de un estado con su icono. Con `live`, el icono se mueve (ejecución abierta). */
export function StatusBadge({ status, live = false }: { status: string; live?: boolean }) {
  const tone = statusTone(status)
  return (
    <Pill tone={tone} icon={statusIcon(status, tone)} live={live && tone === 'active'}>
      {statusLabel(status)}
    </Pill>
  )
}
