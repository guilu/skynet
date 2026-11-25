import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { FloatingSkyny } from './FloatingSkyny'
import { floatGesture, orbitAfter } from './float'

// Lo que recibe el Skyny 3D (sin three): la orientación y los «pop».
const seen = vi.hoisted(() => ({ props: {} as Record<string, unknown> }))
vi.mock('./load3d', () => ({
  Skyny3D: (props: Record<string, unknown>) => {
    seen.props = props
    return null
  },
}))
vi.mock('./look', () => ({ can3D: () => true }))
vi.mock('./context', () => ({ useCompanion: () => ({ expression: 'calm' }) }))

let coarse = false
beforeAll(() => {
  // jsdom no trae PointerEvent ni la captura del puntero.
  class FakePointerEvent extends MouseEvent {
    pointerId: number
    pointerType: string
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init)
      this.pointerId = init.pointerId ?? 1
      this.pointerType = init.pointerType ?? 'mouse'
    }
  }
  globalThis.PointerEvent ??= FakePointerEvent as unknown as typeof PointerEvent
  Element.prototype.setPointerCapture ??= function () {}
  Element.prototype.releasePointerCapture ??= function () {}
  Element.prototype.hasPointerCapture ??= () => false
})
beforeEach(() => {
  coarse = false
  window.matchMedia = ((query: string) => ({
    matches: query === '(pointer: coarse)' ? coarse : false,
    addEventListener() {},
    removeEventListener() {},
  })) as unknown as typeof window.matchMedia
  seen.props = {}
})
afterEach(() => vi.useRealTimers())

const hit = () => document.querySelector('.floating-skyny-hit')!
const box = () => document.querySelector<HTMLElement>('.floating-skyny')!
const pointer = (type: string, x: number, y: number, init: PointerEventInit = {}) =>
  fireEvent(
    hit(),
    new PointerEvent(type, {
      bubbles: true,
      clientX: x,
      clientY: y,
      button: 0,
      pointerType: 'mouse',
      ...init,
    }),
  )

async function mount() {
  render(<FloatingSkyny />)
  // El Skyny 3D va en un Suspense: se espera a que lo pinte.
  await act(async () => {})
}

describe('gestos de Skyny flotando', () => {
  it('con ratón el izquierdo gira y el derecho mueve; con el dedo, según «Girar»', () => {
    expect(floatGesture('mouse', 0, false)).toBe('rotate')
    expect(floatGesture('mouse', 2, false)).toBe('move')
    expect(floatGesture('mouse', 1, false)).toBeNull()
    expect(floatGesture('touch', 0, false)).toBe('move')
    expect(floatGesture('touch', 0, true)).toBe('rotate')
  })

  it('en horizontal gira sobre Y y en vertical se inclina sobre X, con un tope', () => {
    expect(orbitAfter({ yaw: 0, pitch: 0 }, 100, 0)).toEqual({ yaw: -1.2, pitch: 0 })
    expect(orbitAfter({ yaw: 0, pitch: 0 }, 0, 50).pitch).toBeCloseTo(0.6)
    expect(orbitAfter({ yaw: 0, pitch: 0 }, 0, 5000).pitch).toBe(1.1)
  })
})

describe('Skyny flotando', () => {
  it('arrastrar con el botón izquierdo lo gira sin moverlo ni hacer «pop»', async () => {
    await mount()
    const where = box().style.cssText
    pointer('pointerdown', 100, 100)
    pointer('pointermove', 160, 130)
    pointer('pointerup', 160, 130)
    expect(seen.props.orbit).toEqual(orbitAfter({ yaw: 0, pitch: 0 }, 60, 30))
    expect(box().style.cssText).toBe(where)
    expect(seen.props.boop).toBe(0)
  })

  it('arrastrar con el botón derecho lo mueve, conserva el giro y no saca el menú', async () => {
    await mount()
    pointer('pointerdown', 100, 100)
    pointer('pointermove', 140, 100)
    pointer('pointerup', 140, 100)
    const turned = seen.props.orbit
    const left = box().style.left

    pointer('pointerdown', 100, 100, { button: 2 })
    const menu = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    hit().dispatchEvent(menu)
    expect(menu.defaultPrevented).toBe(true)
    pointer('pointermove', 40, 60, { button: 2 })
    expect(box().style.left).not.toBe(left)
    // Suelta fuera de Skyny: el gesto acaba igual (tiene el puntero capturado).
    fireEvent(document.body, new PointerEvent('pointerup', { bubbles: true }))
    pointer('pointerup', 40, 60, { button: 2 })
    expect(box()).not.toHaveAttribute('data-dragging')
    expect(seen.props.orbit).toEqual(turned)
    expect(seen.props.boop).toBe(0)
  })

  it('un clic izquierdo sin arrastrar hace «pop» una vez; el derecho, no', async () => {
    await mount()
    pointer('pointerdown', 100, 100)
    pointer('pointerup', 102, 101)
    expect(seen.props.boop).toBe(1)
    pointer('pointerdown', 100, 100, { button: 2 })
    pointer('pointerup', 100, 100, { button: 2 })
    expect(seen.props.boop).toBe(1)
  })

  it('cancelar el gesto lo limpia sin «pop»', async () => {
    await mount()
    pointer('pointerdown', 100, 100, { button: 2 })
    pointer('pointermove', 50, 50, { button: 2 })
    expect(box()).toHaveAttribute('data-dragging')
    pointer('pointercancel', 50, 50, { button: 2 })
    expect(box()).not.toHaveAttribute('data-dragging')
    expect(seen.props.boop).toBe(0)
  })

  it('con el dedo lo mueve; con «Girar» activado lo gira, y un toque sigue haciendo «pop»', async () => {
    coarse = true
    await mount()
    const touch = { pointerType: 'touch' }
    const turn = screen.getByRole('button', { name: 'Girar' })
    expect(turn).toHaveAttribute('aria-pressed', 'false')

    const left = box().style.left
    pointer('pointerdown', 100, 100, touch)
    pointer('pointermove', 40, 100, touch)
    pointer('pointerup', 40, 100, touch)
    expect(box().style.left).not.toBe(left)
    expect(seen.props.orbit).toEqual({ yaw: 0, pitch: 0 })

    fireEvent.click(turn)
    expect(turn).toHaveAttribute('aria-pressed', 'true')
    const moved = box().style.left
    pointer('pointerdown', 100, 100, touch)
    pointer('pointermove', 150, 100, touch)
    pointer('pointerup', 150, 100, touch)
    expect(box().style.left).toBe(moved)
    expect(seen.props.orbit).toEqual(orbitAfter({ yaw: 0, pitch: 0 }, 50, 0))

    pointer('pointerdown', 100, 100, touch)
    pointer('pointerup', 100, 100, touch)
    expect(seen.props.boop).toBe(1)
  })

  it('sin pantalla táctil no enseña «Girar»', async () => {
    await mount()
    expect(screen.queryByRole('button', { name: 'Girar' })).toBeNull()
  })
})
