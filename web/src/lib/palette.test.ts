import { describe, expect, it } from 'vitest'
import {
  AA,
  contrast,
  DEFAULT_BASE,
  derivePalette,
  deriveSlot,
  paletteCss,
  PRESETS,
  SLOTS,
} from './palette'
import indexCss from '../index.css?raw'

/** Valor de un token en un bloque de index.css (el primero que empieza por `selector`). */
function tokenIn(selector: string, token: string): string | undefined {
  const block = indexCss.slice(indexCss.indexOf(selector))
  return new RegExp(`--${token}:\\s*(#[0-9a-f]{6});`).exec(block.slice(0, block.indexOf('}')))?.[1]
}

describe('Paleta personalizable', () => {
  it('con los colores de Skynet, la derivación reproduce los tokens de index.css', () => {
    const defaults = derivePalette({})
    for (const { slot } of SLOTS) {
      for (const mode of ['light', 'dark'] as const) {
        // Por el camino del cálculo, no por el atajo de «sin cambios».
        const { tokens, issue } = deriveSlot(slot, DEFAULT_BASE[slot], mode)
        expect(issue).toBeUndefined()
        for (const [token, value] of Object.entries(tokens)) {
          expect(value, `${mode} --${token}`).toBe(defaults[mode][token])
        }
      }
    }
    // Y esos tokens son los de index.css: si cambian allí, hay que cambiarlos en palette.ts.
    for (const [token, value] of Object.entries(defaults.light)) {
      expect(value, `--${token}`).toBe(tokenIn(':root {', token))
    }
    for (const [token, value] of Object.entries(defaults.dark)) {
      expect(value, `oscuro --${token}`).toBe(tokenIn(":root[data-theme='dark'] {", token))
    }
    expect(paletteCss({})).toBe('')
  })

  it('todas las paletas predefinidas llegan a AA en los dos temas', () => {
    for (const preset of PRESETS) {
      const { light, dark, issues } = derivePalette(preset.colors)
      expect(issues, preset.name).toEqual([])
      for (const tokens of [light, dark]) {
        expect(contrast(tokens['on-primary'], tokens.primary)).toBeGreaterThanOrEqual(AA)
        for (const { slot } of SLOTS) {
          expect(contrast(tokens[`${slot}-ink`], tokens[`${slot}-soft`])).toBeGreaterThanOrEqual(AA)
        }
      }
    }
  })

  it('con un color difícil corrige la tinta y los botones hasta AA', () => {
    const { light, dark } = derivePalette({ ok: '#ffe600', bad: '#ffc0cb', primary: '#ffe600' })
    expect(light.ok).toBe('#ffe600')
    expect(contrast(light['ok-ink'], light['ok-soft'])).toBeGreaterThanOrEqual(AA)
    expect(contrast(light['ok-ink'], '#ffffff')).toBeGreaterThanOrEqual(AA)
    expect(contrast('#ffffff', light['ok-btn'])).toBeGreaterThanOrEqual(AA)
    expect(contrast('#ffffff', light['bad-btn'])).toBeGreaterThanOrEqual(AA)
    expect(contrast('#0b1022', dark['bad-btn'])).toBeGreaterThanOrEqual(AA)
    expect(contrast(dark['ok-ink'], dark['ok-soft'])).toBeGreaterThanOrEqual(AA)
    // Sobre amarillo, el texto del botón principal pasa a oscuro.
    expect(light['on-primary']).toBe('#0b1022')
  })

  it('el CSS solo cambia las piezas elegidas y gana a index.css en los dos temas', () => {
    const out = paletteCss({ primary: '#C2255C' })
    expect(out).toContain(':root:root {')
    expect(out).toContain(
      "@media (prefers-color-scheme: dark) {\n:root:root:not([data-theme='light'])",
    )
    expect(out).toContain(":root:root[data-theme='dark']")
    expect(out).toContain('--primary: #c2255c;')
    expect(out).toContain('--on-primary:')
    expect(out).not.toContain('--ok:')
  })
})
