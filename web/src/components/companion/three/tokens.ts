// Colores de Skyny 3D leídos de los tokens del tema: el cuerpo de cada estado es su
// `--skyny-3d-<estado>` (los colores claros del estudio v4.3, en claro y oscuro); los ojos, los
// nodos y el confeti, los tokens del companion.
import { STATES_META, type SkynyState } from './states'

export interface SkynyColors {
  /** Cuerpo de cada estado (`--skyny-3d-<estado>`). */
  body: Record<SkynyState, string>
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

  const primary = token('--primary', '#335fe0')
  const body = {} as Record<SkynyState, string>
  for (const { state } of STATES_META) body[state] = token(`--skyny-3d-${state}`, primary)

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
