import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SkynyPaused } from '../pause'
import Skyny3D from './Skyny3D'

const scene = vi.hoisted(() => ({ play: vi.fn(), stop: vi.fn() }))

vi.mock('./scene', () => ({
  SkynyScene: class {
    current = 'calm'
    play = scene.play
    stop = scene.stop
    resize() {}
    setState() {}
    setCompact() {}
    setProgress() {}
    setPose() {}
    setColors() {}
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
