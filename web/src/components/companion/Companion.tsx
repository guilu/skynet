import { useEffect, useState } from 'react'
import { cn } from '../../lib/cn'
import { companionLabel, type CompanionState } from './state'

/** Por debajo de este tamaño se quitan los efectos, el brillo y la sombra. */
const SMALL = 32
/** Lo que tarda en cerrarse el visor al cambiar de expresión; el cambio queda tapado. */
export const SWAP_MS = 110

// Pausa todas las animaciones con la pestaña oculta.
if (typeof document !== 'undefined') {
  const sync = () => document.documentElement.toggleAttribute('data-hidden', document.hidden)
  document.addEventListener('visibilitychange', sync)
}

/**
 * La mascota: el isotipo de Skynet hecho personaje. El nodo de arriba es la cabeza, los de abajo
 * las manos y el anillo la cara, con un visor donde cambian los ojos. Pinta con los tokens, así
 * sigue al acento (UI-G) y al tema. Cada cambio de `state` pasa por un parpadeo del visor.
 *
 * Con `label` se anuncia como imagen; sin él es decorativa y el texto va al lado. `still` la deja
 * quieta salvo al pasar el ratón (listas, nodos, galerías).
 */
export function Companion({
  state,
  size = 96,
  label,
  still = false,
  className,
}: {
  state: CompanionState
  size?: number
  label?: string
  still?: boolean
  className?: string
}) {
  const [shown, setShown] = useState(state)
  const [opening, setOpening] = useState(false)

  // Cierra el visor, cambia los ojos a mitad del parpadeo y lo vuelve a abrir.
  useEffect(() => {
    if (state === shown) return
    const t = window.setTimeout(() => {
      setShown(state)
      setOpening(true)
    }, SWAP_MS)
    return () => window.clearTimeout(t)
  }, [state, shown])

  useEffect(() => {
    if (!opening) return
    const t = window.setTimeout(() => setOpening(false), SWAP_MS)
    return () => window.clearTimeout(t)
  }, [opening])

  const swapping = state !== shown || opening
  const small = size < SMALL
  const tiny = size <= 16
  return (
    <svg
      className={cn('companion', swapping && 'companion-swap', className)}
      data-state={shown}
      data-still={still || undefined}
      data-small={small || undefined}
      width={size}
      height={size}
      viewBox={small ? '2 2 116 120' : '-20 -22 160 160'}
      {...(label
        ? { role: 'img', 'aria-label': `${label}: ${companionLabel(shown)}` }
        : { 'aria-hidden': true })}
      focusable="false"
    >
      {!small && <ellipse className="companion-floor" cx="60" cy="122" rx="44" ry="5" />}
      <g className="companion-body">
        {!small && (
          <polygon
            className="companion-shade"
            points="60,30 22,92 98,92"
            transform="translate(0 4)"
          />
        )}
        <polygon className="companion-shell" points="60,30 22,92 98,92" />
        {!small && <path className="companion-shine" d="M44 44 Q52 30 60 26" />}
        <g className="companion-head">
          <Node cx={60} cy={22} small={small} tiny={tiny} />
        </g>
        <circle className="companion-ring" cx="60" cy="66" r="28" />
        <circle className="companion-visor" cx="60" cy="66" r={tiny ? 20 : 22} />
        <g className="companion-eyes">
          <Eyes state={shown} tiny={tiny} />
        </g>
        <g className="companion-hand companion-hand-l">
          <Node cx={20} cy={96} small={small} tiny={tiny} />
        </g>
        <g className="companion-hand companion-hand-r">
          <Node cx={100} cy={96} small={small} tiny={tiny} />
        </g>
      </g>
      {!small && <Effects state={shown} />}
    </svg>
  )
}

/** Un nodo del logo: bola blanca con contorno y, en grande, su sombra y su brillo. */
function Node({ cx, cy, small, tiny }: { cx: number; cy: number; small: boolean; tiny: boolean }) {
  const r = 15
  if (small)
    return <circle className="companion-node" cx={cx} cy={cy} r={r} strokeWidth={tiny ? 6 : 4} />
  return (
    <>
      <circle className="companion-node-shade" cx={cx} cy={cy + 4} r={r} />
      <circle className="companion-node" cx={cx} cy={cy} r={r} strokeWidth={3} />
      <circle className="companion-node-shine" cx={cx - r * 0.35} cy={cy - r * 0.35} r={r * 0.22} />
    </>
  )
}

function Eyes({ state, tiny }: { state: CompanionState; tiny: boolean }) {
  const w = tiny ? 10 : 8
  const pill = (x: number, y: number, width: number, height: number) => (
    <rect className="companion-eye" x={x} y={y} width={width} height={height} rx={width / 2} />
  )
  const line = (d: string) => <path className="companion-eye-line" d={d} />
  switch (state) {
    case 'ready':
      return (
        <>
          {pill(51 - w / 2, 59, w, 13)}
          {pill(69 - w / 2, 59, w, 13)}
        </>
      )
    case 'waiting':
      return (
        <>
          {pill(47, 57, 9, 15)}
          {pill(64, 57, 9, 15)}
          <circle className="companion-eye-glint" cx="53.5" cy="61" r="1.8" />
          <circle className="companion-eye-glint" cx="70.5" cy="61" r="1.8" />
        </>
      )
    case 'thinking':
      return (
        <>
          {pill(48, 55, 8, 11)}
          {pill(64, 55, 8, 11)}
        </>
      )
    case 'reviewing':
      return (
        <>
          {pill(47, 64, 9, 8)}
          {pill(64, 64, 9, 8)}
        </>
      )
    case 'running':
    case 'success':
      return line('M46 69 Q51 59 56 69 M64 69 Q69 59 74 69')
    case 'sleeping':
      return line('M46 64 Q51 71 56 64 M64 64 Q69 71 74 64')
    case 'error':
      return line('M46 60 L55 65.5 L46 71 M74 60 L65 65.5 L74 71')
    case 'paused':
    case 'cancelled':
      return line('M46 66 H56 M64 66 H74')
    case 'offline':
      return (
        <>
          <circle className="companion-eye" cx="51" cy="66" r="3" />
          <circle className="companion-eye" cx="69" cy="66" r="3" />
        </>
      )
  }
}

/** Lo que acompaña a cada expresión fuera del cuerpo: burbujas, insignias, confeti… */
function Effects({ state }: { state: CompanionState }) {
  switch (state) {
    case 'thinking':
      return (
        <g className="companion-fx companion-fx-think">
          <circle className="companion-badge" cx="96" cy="14" r="11" />
          <circle cx="90" cy="14" r="2.6" />
          <circle cx="96" cy="14" r="2.6" />
          <circle cx="102" cy="14" r="2.6" />
        </g>
      )
    case 'running':
      return (
        <g className="companion-fx companion-fx-speed">
          <line x1="-8" y1="50" x2="8" y2="50" />
          <line x1="-16" y1="64" x2="2" y2="64" />
          <line x1="-8" y1="78" x2="6" y2="78" />
        </g>
      )
    case 'reviewing':
      return (
        <g className="companion-fx companion-fx-laptop">
          <path className="companion-laptop" d="M34 86 L86 86 L82 112 L38 112 Z" />
          <rect className="companion-laptop-base" x="26" y="110" width="68" height="6" rx="3" />
          <circle className="companion-laptop-led" cx="60" cy="98" r="3" />
        </g>
      )
    case 'success':
      return (
        <g className="companion-fx companion-fx-confetti">
          <rect
            x="14"
            y="8"
            width="6"
            height="10"
            rx="2"
            fill="var(--warn)"
            transform="rotate(-20 17 13)"
          />
          <circle cx="104" cy="12" r="4" fill="var(--ok)" />
          <rect
            x="96"
            y="30"
            width="5"
            height="9"
            rx="2"
            fill="var(--live)"
            transform="rotate(25 98 34)"
          />
          <circle cx="20" cy="34" r="3.5" fill="var(--primary)" />
          <rect
            x="82"
            y="-2"
            width="5"
            height="9"
            rx="2"
            fill="var(--bad)"
            transform="rotate(15 84 2)"
          />
          <circle cx="34" cy="2" r="3" fill="var(--live)" />
        </g>
      )
    case 'error':
      return (
        <g className="companion-fx companion-fx-alarm">
          <line x1="14" y1="22" x2="22" y2="32" />
          <line x1="4" y1="40" x2="15" y2="42" />
          <line x1="106" y1="22" x2="98" y2="32" />
          <line x1="116" y1="40" x2="105" y2="42" />
        </g>
      )
    case 'paused':
      return (
        <g className="companion-fx companion-fx-pause">
          <circle cx="96" cy="14" r="11" />
          <rect x="91" y="9" width="3.5" height="10" rx="1.5" />
          <rect x="97.5" y="9" width="3.5" height="10" rx="1.5" />
        </g>
      )
    case 'waiting':
      return (
        <g className="companion-fx companion-fx-bang">
          <circle cx="30" cy="12" r="11" />
          <rect x="28" y="4" width="4" height="10" rx="2" />
          <circle cx="30" cy="18" r="2.2" />
        </g>
      )
    case 'sleeping':
      return (
        <g className="companion-fx companion-fx-zz">
          <text x="88" y="16" fontSize="18">
            z
          </text>
          <text x="98" y="4" fontSize="24">
            Z
          </text>
        </g>
      )
    case 'offline':
      return (
        <g className="companion-fx companion-fx-off">
          <circle cx="96" cy="14" r="11" />
          <path d="M89 13 q7 -6 14 0 M92 17 q4 -3 8 0" />
          <line x1="89" y1="7" x2="103" y2="21" />
        </g>
      )
    default:
      return null
  }
}
