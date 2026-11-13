import { describe, expect, it } from 'vitest'
import { COMPANION_STATES } from '../state'
import {
  accessoryFade,
  blend,
  BLEND_MS,
  ENTRY_MS,
  entryGesture,
  errorShake,
  executionBurst,
  executionTravel,
  handLift,
  popIn,
  restLook,
  reveal,
  successHop,
  thinkingLook,
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

describe('transiciones del estudio v2', () => {
  it('colores y postura se funden en 300 ms; el accesorio que se va, en 220', () => {
    expect(blend(0)).toBe(0)
    expect(blend(BLEND_MS)).toBe(1)
    expect(accessoryFade(0)).toBe(1)
    expect(accessoryFade(220)).toBe(0)
  })

  it('la mano sube del todo a los 420 ms y ahí se queda', () => {
    expect(handLift(0)).toBe(0)
    expect(handLift(420)).toBe(1)
    expect(handLift(Infinity)).toBe(1)
  })

  it('en reposo mira a un lado y parpadea una vez por ciclo', () => {
    expect(restLook(0)).toEqual({ gaze: 0, blinking: false })
    expect(restLook(2600).gaze).toBe(7)
    expect(restLook(4950).blinking).toBe(true)
    expect(restLook(7600 + 2600).gaze).toBe(7)
  })

  it('pensando, levanta la mirada arriba a la izquierda y vuelve', () => {
    expect(thinkingLook(0)).toEqual([0, 0])
    expect(thinkingLook(3400)).toEqual([-10, -6])
    expect(thinkingLook(6000)).toEqual([0, 0])
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
