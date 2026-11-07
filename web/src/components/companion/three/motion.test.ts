import { describe, expect, it } from 'vitest'
import { COMPANION_STATES } from '../state'
import {
  ENTRY_MS,
  entryGesture,
  errorShake,
  executionBurst,
  executionTravel,
  popIn,
  reveal,
  successHop,
} from './motion'
import { EXPRESSIONS_3D } from './states'

describe('gestos de Skyny 3D', () => {
  it('empiezan y acaban en reposo', () => {
    for (const f of [entryGesture, executionBurst, executionTravel, errorShake, successHop]) {
      expect(f(0)).toBeCloseTo(0, 6)
      expect(f(ENTRY_MS)).toBeCloseTo(0, 6)
    }
  })

  it('con el gesto acabado (tiempo infinito) todo queda en su sitio y visible', () => {
    expect(entryGesture(Infinity)).toBe(0)
    expect(executionTravel(Infinity)).toBeCloseTo(0, 6)
    expect(errorShake(Infinity)).toBeCloseTo(0, 6)
    expect(successHop(Infinity)).toBeCloseTo(0, 6)
    expect(reveal(Infinity, 2)).toBe(1)
    expect(popIn(Infinity)).toBe(1)
  })

  it('el gesto de entrada llega a su máximo hacia la mitad', () => {
    expect(entryGesture(700)).toBe(1)
  })

  it('los accesorios aparecen uno detrás de otro', () => {
    expect(reveal(200, 0)).toBeGreaterThan(reveal(200, 1))
    expect(reveal(200, 1)).toBe(0)
  })
})

describe('expresiones 3D', () => {
  it('hay una para cada estado del companion', () => {
    for (const { state } of COMPANION_STATES) expect(EXPRESSIONS_3D[state]).toBeDefined()
    expect(Object.keys(EXPRESSIONS_3D)).toHaveLength(COMPANION_STATES.length)
  })

  it('Reposo y En espera no hacen gesto ni llevan accesorio', () => {
    for (const state of ['calm', 'ready'] as const) {
      expect(EXPRESSIONS_3D[state].entry).toBe(false)
      expect(EXPRESSIONS_3D[state].accessory).toBeNull()
    }
  })
})
