import { CHATGPT_GRADIENTS } from './chatgptColors'

const DEFS_ID = 'cg-defs'

/**
 * Pone una sola vez en la página los degradados del paquete «ChatGPT», en un SVG invisible pero
 * no `display: none` (así los navegadores sí los pintan). Los dibujos los usan con `url(#cg-…)`.
 */
export function ensureChatgptDefs() {
  if (typeof document === 'undefined' || document.getElementById(DEFS_ID)) return
  const stops = Object.entries(CHATGPT_GRADIENTS)
    .map(
      ([name, [top, bottom]]) =>
        `<linearGradient id="cg-${name}" x1="0" y1="0" x2="0.35" y2="1">` +
        `<stop offset="0" stop-color="${top}"/><stop offset="1" stop-color="${bottom}"/></linearGradient>`,
    )
    .join('')
  const holder = document.createElement('div')
  holder.innerHTML = `<svg id="${DEFS_ID}" width="0" height="0" aria-hidden="true" focusable="false" style="position:absolute;width:0;height:0;overflow:hidden"><defs>${stops}</defs></svg>`
  document.body.appendChild(holder.firstChild!)
}
