import { useId } from 'react'
import {
  MARK_ARM_WIDTH,
  MARK_ARMS,
  MARK_NODE_R,
  MARK_NODES,
  MARK_NOTCH,
  MARK_NOTCH_WIDTH,
  MARK_RING,
  MARK_VIEWBOX,
} from '../../lib/brand'

/**
 * Isotipo de Skynet. Pinta con `currentColor`, así toma el color del texto de donde se ponga
 * (sobre la baldosa de marca, el de `--on-primary`). Decorativo: el nombre va siempre al lado.
 */
export function Logo({ size = 24, className }: { size?: number; className?: string }) {
  const mask = useId()
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox={MARK_VIEWBOX}
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <mask id={mask}>
        <rect width="64" height="64" fill="#fff" />
        <circle cx={MARK_RING.cx} cy={MARK_RING.cy} r={MARK_RING.hole} fill="#000" />
        <path d={MARK_NOTCH} stroke="#000" strokeWidth={MARK_NOTCH_WIDTH} />
      </mask>
      <g mask={`url(#${mask})`}>
        <path
          d={MARK_ARMS}
          stroke="currentColor"
          strokeWidth={MARK_ARM_WIDTH}
          strokeLinecap="round"
        />
        <circle cx={MARK_RING.cx} cy={MARK_RING.cy} r={MARK_RING.r} />
        {MARK_NODES.map((n) => (
          <circle key={`${n.cx}-${n.cy}`} cx={n.cx} cy={n.cy} r={MARK_NODE_R} />
        ))}
      </g>
    </svg>
  )
}

/** Baldosa de marca: el isotipo sobre el acento, con su degradado y su sombra corta. */
export function BrandMark({ large = false }: { large?: boolean }) {
  return (
    <span className={large ? 'brand-mark brand-mark-lg' : 'brand-mark'} aria-hidden="true">
      <Logo size={large ? 52 : 26} />
    </span>
  )
}
