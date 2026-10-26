import { useEffect, useId, useState, type ReactNode } from 'react'
import { cn } from '../../lib/cn'
import { useSeason, type Season } from './season'
import { companionLabel, type CompanionState } from './state'

/** Por debajo de este tamaño se quitan los efectos, el brillo y el resplandor de los ojos. */
const SMALL = 32
/** Desde este tamaño va completa: con la animación de reposo y los efectos animados. */
const LARGE = 64
/** Lo que tarda en cerrarse el visor al cambiar de expresión; el cambio queda tapado. */
export const SWAP_MS = 110

/** El cuerpo: un triángulo que el trazo grueso redondea. */
const BODY = 'M60 12 L10 94 L110 94 Z'

/**
 * Skyny v3 (plan `plans/siguiente-hito-sy.md`): el isotipo de Skynet hecho personaje. El nodo de
 * arriba es la cabeza, los de abajo las manos, y el visor oscuro con tres bandas es la cara, donde
 * cambian los ojos. El cuerpo toma el color del estado (Reposo y Revisando, el del acento de
 * UI-G). Cada cambio de `state` pasa por un parpadeo del visor.
 *
 * Con `label` se anuncia como imagen; sin él es decorativa y el texto va al lado. `still` la deja
 * quieta salvo al pasar el ratón (listas, nodos, galerías). `season` fuerza un accesorio (o
 * ninguno con null); si no se pasa, sale el de la fecha salvo que se haya quitado en Ajustes.
 */
export function Companion({
  state,
  size = 96,
  label,
  still = false,
  season,
  className,
}: {
  state: CompanionState
  size?: number
  label?: string
  still?: boolean
  season?: Season | null
  className?: string
}) {
  const [shown, setShown] = useState(state)
  const [opening, setOpening] = useState(false)
  const today = useSeason()
  const id = useId()

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
  const hat = small ? null : season === undefined ? today : season
  const ref = (name: string) => `url(#${id}-${name})`
  return (
    <svg
      className={cn('companion', swapping && 'companion-swap', className)}
      data-state={shown}
      data-size={small ? 's' : size < LARGE ? 'm' : 'l'}
      data-still={still || undefined}
      data-small={small || undefined}
      data-season={hat ?? undefined}
      width={size}
      height={size}
      viewBox={small ? '-6 -6 132 124' : '-24 -22 176 154'}
      {...(label
        ? { role: 'img', 'aria-label': `${label}: ${companionLabel(shown)}` }
        : { 'aria-hidden': true })}
      focusable="false"
    >
      <defs>
        <linearGradient id={`${id}-body`} x1="0" y1="0" x2="0.25" y2="1">
          <stop className="companion-stop-hi" offset="0" />
          <stop className="companion-stop-lo" offset="0.85" />
        </linearGradient>
        <mask
          id={`${id}-shape`}
          maskUnits="userSpaceOnUse"
          x="-20"
          y="-20"
          width="160"
          height="160"
        >
          <path d={BODY} fill="#fff" stroke="#fff" strokeWidth="22" strokeLinejoin="round" />
        </mask>
        {!small && (
          <>
            <radialGradient id={`${id}-node`} cx="0.38" cy="0.32" r="0.75">
              <stop className="companion-node-hi" offset="0" />
              <stop className="companion-node-mid" offset="0.7" />
              <stop className="companion-node-lo" offset="1" />
            </radialGradient>
            <filter
              id={`${id}-glow`}
              filterUnits="userSpaceOnUse"
              x="30"
              y="34"
              width="64"
              height="60"
            >
              <feGaussianBlur in="SourceAlpha" stdDeviation="2.2" result="blur" />
              <feFlood className="companion-glow" />
              <feComposite in2="blur" operator="in" result="glow" />
              <feMerge>
                <feMergeNode in="glow" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </>
        )}
      </defs>
      {!small && <ellipse className="companion-floor" cx="60" cy="122" rx="42" ry="5" />}
      <g className="companion-body">
        <path
          className="companion-shell"
          d={BODY}
          fill={ref('body')}
          stroke={ref('body')}
          strokeWidth="22"
          strokeLinejoin="round"
        />
        <g className="companion-bands" mask={ref('shape')} strokeWidth={small ? 13 : 11}>
          <path d="M60 63 L-6 50" />
          <path d="M60 63 L126 50" />
          <path d="M60 64 L60 120" />
        </g>
        {!small && <circle className="companion-ring" cx="60" cy="64" r="27" />}
        <circle className="companion-visor" cx="60" cy="64" r={small ? 24 : 22} />
        {!small && <path className="companion-visor-shine" d="M44 52 Q50 45 58 44" />}
        <g className="companion-head">
          <Node cx={60} cy={12} r={16} small={small} fill={ref('node')} />
          {hat && (
            // Los sombreros se dibujan a escala de la cabeza y se agrandan desde el ala.
            <g transform="translate(60 -1) scale(1.4) translate(-60 1)">
              <Hat season={hat} />
            </g>
          )}
        </g>
        <g className="companion-eyes" filter={small ? undefined : ref('glow')}>
          <Eyes state={shown} small={small} />
        </g>
        <g className="companion-hand companion-hand-l">
          <Node cx={14} cy={96} r={18} small={small} fill={ref('node')} />
        </g>
        <g className="companion-hand companion-hand-r">
          <Node cx={106} cy={96} r={18} small={small} fill={ref('node')} />
        </g>
      </g>
      {!small && <Effects state={shown} />}
    </svg>
  )
}

/** Un nodo del logo: bola blanca y, en grande, con su degradado y su brillo. */
function Node({
  cx,
  cy,
  r,
  small,
  fill,
}: {
  cx: number
  cy: number
  r: number
  small: boolean
  fill: string
}) {
  if (small) return <circle className="companion-node" cx={cx} cy={cy} r={r} strokeWidth={5} />
  return (
    <>
      <circle className="companion-node" cx={cx} cy={cy} r={r} fill={fill} strokeWidth={2.5} />
      <ellipse
        className="companion-node-shine"
        cx={cx - r * 0.32}
        cy={cy - r * 0.38}
        rx={r * 0.32}
        ry={r * 0.2}
      />
    </>
  )
}

function Eyes({ state, small }: { state: CompanionState; small: boolean }) {
  const pill = (x: number, y = 54, w = small ? 11 : 9, h = 18) => (
    <rect className="companion-eye" x={x} y={y} width={w} height={h} rx={w / 2} />
  )
  const pills = small ? (
    <>
      {pill(46)}
      {pill(63)}
    </>
  ) : (
    <>
      {pill(47.5)}
      {pill(63.5)}
    </>
  )
  const line = (d: string) => (
    <path className="companion-eye-line" d={d} strokeWidth={small ? 6.5 : 4.5} />
  )
  switch (state) {
    case 'calm':
    case 'ready':
    case 'waiting':
      return pills
    case 'reviewing':
      // Mira hacia la lupa.
      return <g transform="translate(4 0)">{pills}</g>
    case 'sleeping':
      return line('M45 62 Q51 69 57 62 M63 62 Q69 69 75 62')
    case 'thinking':
      return (
        <>
          {line('M45 62 Q51 68 57 62')}
          {pill(63.5, 52, small ? 11 : 9, 16)}
        </>
      )
    case 'running':
    case 'success':
      return line('M45 67 Q51 57 57 67 M63 67 Q69 57 75 67')
    case 'error':
      return line('M46 58 L55 63.5 L46 69 M74 58 L65 63.5 L74 69')
    case 'paused':
    case 'cancelled':
      return line('M46 64 H56 M64 64 H74')
    case 'offline':
      return (
        <>
          <circle className="companion-eye" cx="51" cy="64" r={small ? 5.5 : 4.5} />
          <circle className="companion-eye" cx="69" cy="64" r={small ? 5.5 : 4.5} />
        </>
      )
  }
}

/** Bocadillo redondo con su pico hacia la cabeza. */
function Bubble({ cx, cy, children }: { cx: number; cy: number; children: ReactNode }) {
  return (
    <>
      <path className="companion-bubble" d={`M${cx - 13} ${cy + 9} q-3 7 -7 9 q8 0 12 -4`} />
      <circle className="companion-bubble" cx={cx} cy={cy} r="14" />
      {children}
    </>
  )
}

/** Lo que acompaña a cada expresión fuera del cuerpo: bocadillos, burbujas, lupa, confeti… */
function Effects({ state }: { state: CompanionState }) {
  switch (state) {
    case 'calm':
      return (
        <g className="companion-fx companion-fx-spark">
          <path d="M92 8 L98 -2" />
          <path d="M98 20 L109 16" />
        </g>
      )
    case 'sleeping':
      return (
        <g className="companion-fx companion-fx-zz">
          <text x="84" y="14" fontSize="14">
            z
          </text>
          <text x="94" y="4" fontSize="20">
            Z
          </text>
        </g>
      )
    case 'ready':
      return (
        <g className="companion-fx companion-fx-ask">
          <Bubble cx={98} cy={8}>
            <text x="98" y="14.5" textAnchor="middle" fontSize="19">
              ?
            </text>
          </Bubble>
        </g>
      )
    case 'thinking':
      return (
        <g className="companion-fx companion-fx-think">
          <circle cx="88" cy="16" r="5" />
          <circle cx="104" cy="0" r="10" />
          <circle className="companion-fx-shine" cx="101" cy="-3" r="3" />
        </g>
      )
    case 'running':
      return (
        <g className="companion-fx companion-fx-speed">
          <path d="M-4 44 H14" />
          <path d="M-14 58 H8" />
          <path d="M-6 72 H10" />
        </g>
      )
    case 'reviewing':
      // La lupa, en la mano derecha y apuntando a la derecha.
      return (
        <g className="companion-fx companion-fx-lupa">
          <path className="companion-lupa-handle" d="M116 88 L124 80" />
          <circle className="companion-lupa-lens" cx="133" cy="70" r="15" />
          <path className="companion-lupa-shine" d="M125 64 Q129 58 136 58" />
        </g>
      )
    case 'success':
      return (
        <g className="companion-fx companion-fx-confetti">
          <rect
            x="8"
            y="4"
            width="5"
            height="11"
            rx="2.5"
            fill="var(--warn)"
            transform="rotate(-25 10 9)"
          />
          <rect x="34" y="-10" width="5" height="11" rx="2.5" fill="var(--ok)" />
          <rect
            x="82"
            y="-8"
            width="5"
            height="11"
            rx="2.5"
            fill="var(--bad)"
            transform="rotate(25 84 -3)"
          />
          <rect
            x="104"
            y="14"
            width="5"
            height="11"
            rx="2.5"
            fill="var(--primary)"
            transform="rotate(50 106 19)"
          />
          <path
            d="M-2 38 l3 6 6 1 -4.5 4 1 6.5 -5.5 -3 -5.5 3 1 -6.5 -4.5 -4 6 -1z"
            fill="var(--primary)"
          />
          <circle cx="112" cy="40" r="3" fill="var(--warn)" />
        </g>
      )
    case 'error':
      return (
        <g className="companion-fx companion-fx-alarm">
          <path d="M14 8 L22 20" />
          <path d="M2 28 L14 32" />
          <path d="M106 8 L98 20" />
          <path d="M118 28 L106 32" />
        </g>
      )
    case 'waiting':
      return (
        <g className="companion-fx companion-fx-bang">
          <Bubble cx={100} cy={6}>
            <rect x="97.5" y="-3" width="5" height="11" rx="2.5" />
            <circle cx="100" cy="13" r="2.8" />
          </Bubble>
        </g>
      )
    case 'paused':
      return (
        <g className="companion-fx companion-fx-pause">
          <Bubble cx={100} cy={6}>
            <rect x="94" y="-1" width="4.5" height="14" rx="2" />
            <rect x="101.5" y="-1" width="4.5" height="14" rx="2" />
          </Bubble>
        </g>
      )
    case 'offline':
      return (
        <g className="companion-fx companion-fx-off">
          <circle cx="102" cy="4" r="12" />
          <path d="M94 -4 L110 12" />
        </g>
      )
    default:
      return null
  }
}

/** Accesorio de temporada: un sombrero sentado sobre la cabeza. */
function Hat({ season }: { season: Season }) {
  switch (season) {
    case 'halloween':
      return (
        <g className="companion-hat" data-hat="halloween">
          <path
            className="companion-hat-main"
            d="M46 -3 L58 -30 Q65 -37 73 -30 Q64 -27 64 -20 L74 -3 Z"
          />
          <path
            className="companion-hat-band"
            d="M49.5 -12 Q60 -9.5 70.5 -12 L72.5 -7.5 Q60 -5 47.5 -7.5 Z"
          />
          <ellipse className="companion-hat-main" cx="60" cy="-2" rx="22" ry="5" />
        </g>
      )
    case 'christmas':
      return (
        <g className="companion-hat" data-hat="christmas">
          <path className="companion-hat-main" d="M45 -4 Q50 -30 70 -30 Q82 -28 84 -14 L76 -4 Z" />
          <circle className="companion-hat-trim" cx="84" cy="-12" r="5" />
          <rect className="companion-hat-trim" x="42" y="-7" width="36" height="8" rx="4" />
        </g>
      )
    case 'summer':
      return (
        <g className="companion-hat" data-hat="summer">
          <ellipse className="companion-hat-main" cx="60" cy="-2" rx="26" ry="5.5" />
          <path className="companion-hat-top" d="M47 -3 Q47 -20 60 -20 Q73 -20 73 -3 Z" />
          <path className="companion-hat-band" d="M47.5 -8 H72.5 L73 -4 H47 Z" />
        </g>
      )
  }
}
