import { describe, expect, it } from 'vitest'
import { fitTabs } from './tabsFit'

// Cinco pestañas de 100 px; con flecha, 120; «Más», 60; huecos de 4 y 12 de marco.
const widths = {
  tabs: [100, 100, 100, 100, 100],
  withChevron: [120, 120, 120, 120, 120],
  more: 60,
  gap: 4,
  frame: 12,
}

describe('fitTabs', () => {
  it('sin menú si caben todas', () => {
    expect(fitTabs(widths, 528, 0)).toBe(5)
  })

  it('deja sitio al botón «Más»', () => {
    // 3 pestañas + 3 huecos + «Más» + marco = 384.
    expect(fitTabs(widths, 384, 0)).toBe(3)
    expect(fitTabs(widths, 383, 0)).toBe(2)
  })

  it('si la elegida va al menú, el botón lleva su nombre y ocupa más', () => {
    expect(fitTabs(widths, 384, 4)).toBe(2)
    expect(fitTabs(widths, 444, 4)).toBe(3)
  })

  it('siempre queda la primera; sin medidas (jsdom), todas', () => {
    expect(fitTabs(widths, 50, 0)).toBe(1)
    expect(fitTabs(widths, 0, 0)).toBe(5)
  })
})
