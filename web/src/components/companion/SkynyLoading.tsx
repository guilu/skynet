import { cn } from '../../lib/cn'
import { Companion } from './Companion'

/**
 * Skyny trabajando mientras algo carga, en lugar del «Cargando…» suelto (SY-C). Con «reducir
 * movimiento» se queda quieta; el texto se anuncia una vez.
 */
export function SkynyLoading({
  label,
  size = 40,
  className,
}: {
  /** Qué se carga: «Cargando el evento #12…». Sin él, «Cargando…». */
  label?: string
  size?: number
  className?: string
}) {
  return (
    <div className={cn('skyny-loading', className)} role="status">
      <Companion state="running" size={size} season={null} />
      <span className="muted">{label ? `Cargando ${label}…` : 'Cargando…'}</span>
    </div>
  )
}
