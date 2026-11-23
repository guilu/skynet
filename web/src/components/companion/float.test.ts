import { describe, expect, it } from 'vitest'
import { floatBox, floatPos } from './float'

const view = { w: 1000, h: 800 }

describe('Skyny flotando', () => {
  it('por defecto, abajo a la derecha dentro de los bordes', () => {
    expect(floatBox({ x: 1, y: 1 }, view, 120)).toEqual({ left: 868, top: 668 })
    expect(floatBox({ x: 0, y: 0 }, view, 120)).toEqual({ left: 12, top: 12 })
  })

  it('no se sale de la pantalla aunque lo arrastres fuera', () => {
    expect(floatPos(-300, 5000, view, 120)).toEqual({ x: 0, y: 1 })
    expect(floatPos(2000, -40, view, 120)).toEqual({ x: 1, y: 0 })
  })

  it('en el móvil no se mete debajo de la barra de abajo', () => {
    expect(floatBox({ x: 1, y: 1 }, { w: 390, h: 844 }, 84, 84).top).toBe(844 - 84 - 12 - 84)
  })

  it('al cambiar la ventana sigue en su rincón', () => {
    const p = floatPos(868, 668, view, 120)
    expect(floatBox(p, { w: 1400, h: 900 }, 120)).toEqual({ left: 1268, top: 768 })
  })
})
