import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SkynyPaused } from '../pause'
import Skyny3D from './Skyny3D'

const scene = vi.hoisted(() => ({ play: vi.fn(), stop: vi.fn() }))

vi.mock('./scene', () => ({
  SkynyScene: class {
    state = 'calm'
    play = scene.play
    stop = scene.stop
    resize() {}
    setState() {}
    setCompact() {}
    setProgress() {}
    setPose() {}
    setColors() {}
    setHat() {}
    setFace() {}
    setBack() {}
    pointer() {}
    lookAt() {}
    dispose() {}
  },
}))

describe('Skyny 3D bajo la cortina del chat', () => {
  it('se para mientras la cortina está abierta y vuelve a moverse al cerrarla', () => {
    const view = (paused: boolean) => (
      <SkynyPaused.Provider value={paused}>
        <Skyny3D size={120} onFail={() => {}} />
      </SkynyPaused.Provider>
    )
    const { rerender } = render(view(true))
    expect(scene.play).not.toHaveBeenCalled()
    expect(scene.stop).toHaveBeenCalled()

    rerender(view(false))
    expect(scene.play).toHaveBeenCalledTimes(1)
    rerender(view(true))
    expect(scene.stop).toHaveBeenCalledTimes(2)
  })
})

describe('Skyny 3D con sitio alrededor', () => {
  it('el lienzo es más grande que su hueco y se sale por igual por los lados', () => {
    const { container } = render(<Skyny3D size={200} onFail={() => {}} />)
    const canvas = container.querySelector('canvas')!
    expect(canvas).toHaveAttribute('width', '300')
    expect(canvas.style.width).toBe('300px')
    expect(canvas.style.margin).toBe('-50px')
  })

  it('sin margen, el lienzo mide lo mismo que su hueco', () => {
    const { container } = render(<Skyny3D size={120} room={1} onFail={() => {}} />)
    const canvas = container.querySelector('canvas')!
    expect(canvas).toHaveAttribute('width', '120')
    expect(canvas.style.margin).toBe('0px')
  })
})
