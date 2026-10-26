/*
 * Isotipo de Skynet (estudio de marca, variante «Principal»): tres nodos unidos a un anillo
 * central, para el componente `Logo`. El icono de la app es Skyny desde SY-B (más abajo).
 */

/** Lienzo del isotipo. */
export const MARK_VIEWBOX = '0 0 64 64'

/** Anillo central: radio exterior y del hueco. */
export const MARK_RING = { cx: 32, cy: 35, r: 12.5, hole: 5.5 }

/** Los tres nodos. */
export const MARK_NODES = [
  { cx: 32, cy: 12 },
  { cx: 12.5, cy: 48 },
  { cx: 51.5, cy: 48 },
]
export const MARK_NODE_R = 8.5

/** Brazos del anillo a los nodos y muesca entre el anillo y el nodo de arriba. */
export const MARK_ARMS = 'M32 35V14M32 35 13 47M32 35l19 12'
export const MARK_ARM_WIDTH = 7.5
export const MARK_NOTCH = 'M26 22.5h12'
export const MARK_NOTCH_WIDTH = 2.5

/*
 * Skyny v3 (hito SY): la mascota, que desde SY-B es también el icono de la app. Una sola geometría
 * para el componente `Companion` y para los iconos (favicon, favicon de estado y PNG).
 */

/** El cuerpo: un triángulo que un trazo de `SKYNY_BODY_STROKE` redondea. */
export const SKYNY_BODY = 'M60 12 L10 94 L110 94 Z'
export const SKYNY_BODY_STROKE = 22

/** Las tres bandas, del visor al borde: las laterales suben un poco y la inferior baja. */
export const SKYNY_BANDS = ['M60 63 L-6 50', 'M60 63 L126 50', 'M60 64 L60 120']

/** Cabeza y manos. */
export const SKYNY_NODES = [
  { cx: 60, cy: 12, r: 16 },
  { cx: 14, cy: 96, r: 18 },
  { cx: 106, cy: 96, r: 18 },
]

/** El visor: centro, radio y aro claro de alrededor. */
export const SKYNY_VISOR = { cx: 60, cy: 64, r: 22, ring: 27 }

/** Colores fijos del icono: el favicon y los PNG no ven los tokens de la página. */
export const SKYNY_INK = { visor: '#1c2345', band: '#1f2647', eye: '#c9f1ff', node: '#ffffff' }

/** Mezcla `hex` con blanco: `amount` 0 deja el color y 1 da blanco. */
export function tint(hex: string, amount: number): string {
  const n = parseInt(hex.slice(1), 16)
  const channel = (shift: number) => {
    const c = (n >> shift) & 255
    return Math.round(c + (255 - c) * amount)
  }
  return `#${((channel(16) << 16) | (channel(8) << 8) | channel(0)).toString(16).padStart(6, '0')}`
}

/** Ojos del icono: los de Reposo (y Te necesita) o la «> <» de Error. */
export type SkynyEyes = 'calm' | 'error'

/**
 * Skyny como SVG suelto, sin efectos ni animación: el cuerpo con el degradado de `color`, las
 * tres bandas, el visor con su aro, los nodos y los ojos. Para los iconos de la app.
 */
export function skynySvg(color: string, eyes: SkynyEyes = 'calm'): string {
  const hi = tint(color, 0.58)
  const { visor, band, eye, node } = SKYNY_INK
  const nodes = SKYNY_NODES.map(
    (n) =>
      `<circle cx="${n.cx}" cy="${n.cy}" r="${n.r}" fill="${node}" stroke="${hi}" stroke-width="4"/>`,
  ).join('')
  const face =
    eyes === 'error'
      ? `<path d="M46 58 L55 63.5 L46 69 M74 58 L65 63.5 L74 69" fill="none" stroke="${eye}" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/>`
      : `<rect x="47" y="54" width="10" height="19" rx="5" fill="${eye}"/><rect x="63" y="54" width="10" height="19" rx="5" fill="${eye}"/>`
  const body = `d="${SKYNY_BODY}" stroke-width="${SKYNY_BODY_STROKE}" stroke-linejoin="round"`
  return [
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="-10 -10 140 132">',
    '<defs>',
    `<linearGradient id="b" x1="0" y1="0" x2=".25" y2="1"><stop offset="0" stop-color="${hi}"/><stop offset=".85" stop-color="${color}"/></linearGradient>`,
    `<mask id="s" maskUnits="userSpaceOnUse" x="-20" y="-20" width="160" height="160"><path ${body} fill="#fff" stroke="#fff"/></mask>`,
    '</defs>',
    `<path ${body} fill="url(#b)" stroke="url(#b)"/>`,
    `<g mask="url(#s)" stroke="${band}" stroke-width="12" fill="none">`,
    SKYNY_BANDS.map((d) => `<path d="${d}"/>`).join(''),
    '</g>',
    `<circle cx="${SKYNY_VISOR.cx}" cy="${SKYNY_VISOR.cy}" r="${SKYNY_VISOR.ring}" fill="${tint(color, 0.85)}"/>`,
    `<circle cx="${SKYNY_VISOR.cx}" cy="${SKYNY_VISOR.cy}" r="${SKYNY_VISOR.r}" fill="${visor}"/>`,
    face,
    nodes,
    '</svg>',
  ].join('')
}

/** Favicon por defecto: el de `public/favicon.svg`, que un test compara con este. */
export const FAVICON_DEFAULT = { primary: '#335fe0' }

/**
 * Favicon: Skyny en reposo, del color del acento y sin fondo. El de `public/favicon.svg` sale de
 * aquí con el acento por defecto; con otro acento, la web lo genera al vuelo.
 */
export function faviconSvg(primary: string): string {
  return skynySvg(primary)
}
