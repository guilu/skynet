// Colores de Skyny 3D leídos de los tokens del tema: los mismos que usa el Skyny 2D, para que
// el 3D case con las píldoras de estado, el modo oscuro y las paletas personalizadas.
import { COMPANION_STATES, type CompanionState } from '../state'

export interface SkynyColors {
  /** Cuerpo de cada expresión (`--c-lo` de `.companion[data-state]`). */
  body: Record<CompanionState, string>
  /** Nodos: cabeza y manos (`--companion-node`). */
  node: string
  /** Halo y núcleo de los ojos (`--companion-glow`, `--companion-eye`). */
  glow: string
  core: string
  /** Confeti de Éxito. */
  confetti: string[]
  /** Tema oscuro: la sombra del suelo pasa a ser un resplandor. */
  dark: boolean
}

/** Tema oscuro: `data-theme` en `<html>` o, si no hay, el del sistema. */
export function isDark() {
  const theme = document.documentElement.getAttribute('data-theme')
  if (theme) return theme === 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

let probe: CanvasRenderingContext2D | null | undefined

/** Un color CSS cualquiera (hex, rgb, color-mix…) como `#rrggbb`. */
export function toHex(color: string, fallback: string) {
  probe ??= document.createElement('canvas').getContext('2d')
  if (!probe || !color) return fallback
  probe.fillStyle = fallback
  probe.fillStyle = color
  return probe.fillStyle
}

/** Lee los colores del tema actual. */
export function readColors(): SkynyColors {
  const root = getComputedStyle(document.documentElement)
  const token = (name: string, fallback: string) =>
    toHex(root.getPropertyValue(name).trim(), fallback)

  // Un Skyny 2D invisible por estado: su `--c-lo` es el color del cuerpo en ese estado.
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('class', 'companion')
  svg.setAttribute('aria-hidden', 'true')
  svg.style.cssText = 'position:absolute;width:0;height:0;visibility:hidden'
  document.body.appendChild(svg)
  const primary = token('--primary', '#335fe0')
  const body = {} as Record<CompanionState, string>
  for (const { state } of COMPANION_STATES) {
    svg.setAttribute('data-state', state)
    body[state] = toHex(getComputedStyle(svg).getPropertyValue('--c-lo').trim(), primary)
  }
  svg.remove()

  return {
    body,
    node: token('--companion-node', '#ffffff'),
    glow: token('--companion-glow', '#49c2ff'),
    core: token('--companion-eye', '#c9f1ff'),
    confetti: [
      token('--ok', '#22a45a'),
      token('--skyny-yellow', '#f4b400'),
      token('--companion-glow', '#49c2ff'),
      token('--bad', '#ec4646'),
    ],
    dark: isDark(),
  }
}

/** Avisa cuando cambia el tema o la paleta; devuelve cómo dejar de escuchar. */
export function onThemeChange(listener: () => void): () => void {
  const observer = new MutationObserver(listener)
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme', 'data-palette'],
  })
  const media = window.matchMedia?.('(prefers-color-scheme: dark)')
  media?.addEventListener('change', listener)
  return () => {
    observer.disconnect()
    media?.removeEventListener('change', listener)
  }
}
