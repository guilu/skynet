/*
 * Isotipo de Skynet (estudio de marca, variante «Principal»): tres nodos unidos a un anillo
 * central. Una sola geometría para el componente `Logo` y para el favicon.
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

/** Favicon por defecto: el de `public/favicon.svg`, que un test compara con este. */
export const FAVICON_DEFAULT = { primary: '#335fe0', on: '#ffffff' }

/**
 * Favicon como SVG: el isotipo sobre la baldosa redondeada, con un degradado suave del acento.
 * El de `public/favicon.svg` sale de aquí con los colores por defecto; con otro acento, la web
 * lo genera al vuelo.
 */
export function faviconSvg(primary: string, on: string): string {
  const nodes = MARK_NODES.map(
    (n) => `<circle cx="${n.cx}" cy="${n.cy}" r="${MARK_NODE_R}"/>`,
  ).join('')
  return [
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">',
    '<defs>',
    '<linearGradient id="g" x1="0" y1="0" x2=".4" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset=".55" stop-color="#fff" stop-opacity="0"/></linearGradient>',
    `<mask id="m"><rect width="64" height="64" fill="#fff"/><circle cx="${MARK_RING.cx}" cy="${MARK_RING.cy}" r="${MARK_RING.hole}"/><path d="${MARK_NOTCH}" stroke="#000" stroke-width="${MARK_NOTCH_WIDTH}"/></mask>`,
    '</defs>',
    `<rect width="64" height="64" rx="18" fill="${primary}"/>`,
    '<rect width="64" height="64" rx="18" fill="url(#g)"/>',
    `<g transform="translate(9 10) scale(.72)" fill="${on}" mask="url(#m)">`,
    `<path d="${MARK_ARMS}" stroke="${on}" stroke-width="${MARK_ARM_WIDTH}" stroke-linecap="round"/>`,
    `<circle cx="${MARK_RING.cx}" cy="${MARK_RING.cy}" r="${MARK_RING.r}"/>`,
    nodes,
    '</g>',
    '</svg>',
  ].join('')
}
