import { useEffect } from 'react'
import { cn } from '../../lib/cn'
import { CHATGPT } from './chatgpt'
import './chatgpt.css'
import { ensureChatgptDefs } from './chatgptDefs'
import { ILUSTRADOS, type DrawingTone } from './ilustrados'
import './ilustrados.css'
import { useIconPack } from './pack'
import { ICONS, type IconName } from './registry'
import { variantFor, type IconVariant } from './variant'

/**
 * Icono de la web por su significado. Lo pinta el paquete elegido en Ajustes; si a ese paquete le
 * falta el dibujo, el de Lucide. Siempre es decorativo: el nombre va en el texto de al lado o en
 * el `aria-label` del botón.
 */
export function Icon({
  name,
  size = 18,
  strokeWidth,
  filled = false,
  tone,
  variant,
  loop = false,
  className,
}: {
  name: IconName
  size?: number
  /** Grosor del trazo, solo para Lucide. */
  strokeWidth?: number
  /** Relleno en el color del texto, solo para Lucide (las barras de la pausa se ven mejor). */
  filled?: boolean
  /** Tono de las insignias de estado; sin él, el suyo. */
  tone?: DrawingTone
  /** Fuerza una variante del dibujo ilustrado; sin ella, sale del tamaño. */
  variant?: IconVariant
  /** Repite el gesto (estados en vivo de la ejecución abierta). */
  loop?: boolean
  className?: string
}) {
  const pack = useIconPack()
  const chatgpt = pack === 'chatgpt' ? CHATGPT[name] : undefined
  useEffect(() => {
    if (chatgpt) ensureChatgptDefs()
  }, [chatgpt])
  if (chatgpt) {
    const badgeTone = chatgpt.badge ? (tone ?? chatgpt.tone) : undefined
    return (
      <svg
        className={cn(
          'ico ico-cg',
          size <= 20 && 'small',
          badgeTone && `tone-${badgeTone}`,
          loop && 'loop',
          className,
        )}
        width={size}
        height={size}
        viewBox="0 0 32 32"
        aria-hidden="true"
        focusable="false"
        data-icon={name}
      >
        {chatgpt.body}
      </svg>
    )
  }
  const drawing = pack === 'ilustrados' ? ILUSTRADOS[name] : undefined
  if (!drawing) {
    const Lucide = ICONS[name].lucide
    return (
      <Lucide
        size={size}
        strokeWidth={strokeWidth}
        fill={filled ? 'currentColor' : 'none'}
        className={className}
        aria-hidden="true"
      />
    )
  }
  const shown = drawing.util ? 'trazo' : drawing.badge ? 'compacto' : (variant ?? variantFor(size))
  const badgeTone = drawing.badge ? (tone ?? drawing.tone) : undefined
  return (
    <svg
      className={cn(
        'ico',
        shown !== 'ilustrado' && (shown === 'trazo' ? 'trazo' : 'compact'),
        drawing.badge && 'badge',
        badgeTone && `tone-${badgeTone}`,
        loop && 'loop',
        className,
      )}
      width={size}
      height={size}
      viewBox="0 0 32 32"
      aria-hidden="true"
      focusable="false"
      data-icon={name}
    >
      {drawing.body}
    </svg>
  )
}
