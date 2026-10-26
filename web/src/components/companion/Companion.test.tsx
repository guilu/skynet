import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Companion, SWAP_MS } from './Companion'
import { resetCompanionPrefsCache, setCompanionPrefs } from './prefs'
import { COMPANION_STATES } from './state'

const svg = (container: HTMLElement) => container.querySelector<SVGSVGElement>('svg.companion')!

describe('Companion', () => {
  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
    resetCompanionPrefsCache()
  })

  it('es decorativa sin etiqueta y se anuncia con ella', () => {
    const { container, rerender } = render(<Companion state="running" />)
    expect(svg(container).getAttribute('aria-hidden')).toBe('true')
    rerender(<Companion state="running" label="Skynet" />)
    expect(screen.getByRole('img', { name: 'Skynet: Ejecutando' })).toBeInTheDocument()
  })

  it('cambia de expresión con el visor cerrado', () => {
    vi.useFakeTimers()
    const { container, rerender } = render(<Companion state="running" />)
    rerender(<Companion state="waiting" />)
    expect(svg(container).classList).toContain('companion-swap')
    expect(svg(container).dataset.state).toBe('running')
    act(() => vi.advanceTimersByTime(SWAP_MS))
    expect(svg(container).dataset.state).toBe('waiting')
    act(() => vi.advanceTimersByTime(SWAP_MS))
    expect(svg(container).classList).not.toContain('companion-swap')
  })

  it('si cambia otra vez durante el parpadeo, se queda con la última', () => {
    vi.useFakeTimers()
    const { container, rerender } = render(<Companion state="running" />)
    rerender(<Companion state="waiting" />)
    rerender(<Companion state="success" />)
    act(() => vi.advanceTimersByTime(SWAP_MS * 2))
    expect(svg(container).dataset.state).toBe('success')
  })

  it('dibuja cuerpo, tres bandas, visor, cabeza y manos en cada expresión', () => {
    for (const { state } of COMPANION_STATES) {
      const { container, unmount } = render(<Companion state={state} season={null} />)
      expect(container.querySelector('.companion-shell')).not.toBeNull()
      expect(container.querySelectorAll('.companion-bands path')).toHaveLength(3)
      expect(container.querySelector('.companion-visor')).not.toBeNull()
      expect(container.querySelectorAll('.companion-node')).toHaveLength(3)
      expect(container.querySelector('.companion-eyes')!.childElementCount).toBeGreaterThan(0)
      unmount()
    }
  })

  it('cada instancia usa sus propios degradados y máscaras', () => {
    const { container } = render(
      <>
        <Companion state="running" />
        <Companion state="error" />
      </>,
    )
    const ids = [...container.querySelectorAll('[id]')].map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    const [a, b] = container.querySelectorAll('.companion-shell')
    expect(a.getAttribute('fill')).not.toBe(b.getAttribute('fill'))
  })

  it('tres niveles de tamaño: pequeño sin efectos ni brillo, mediano y grande con efectos', () => {
    const { container: small } = render(<Companion state="success" size={24} />)
    expect(svg(small).dataset.size).toBe('s')
    expect(svg(small).hasAttribute('data-small')).toBe(true)
    expect(small.querySelector('.companion-fx')).toBeNull()
    expect(small.querySelector('.companion-ring')).toBeNull()
    expect(small.querySelector('filter')).toBeNull()
    const { container: medium } = render(<Companion state="success" size={48} />)
    expect(svg(medium).dataset.size).toBe('m')
    expect(medium.querySelector('.companion-fx-confetti')).not.toBeNull()
    const { container: big } = render(<Companion state="success" size={64} />)
    expect(svg(big).dataset.size).toBe('l')
    expect(big.querySelector('.companion-ring')).not.toBeNull()
    expect(big.querySelector('filter')).not.toBeNull()
  })

  it('al revisar sujeta la lupa', () => {
    const { container } = render(<Companion state="reviewing" />)
    expect(container.querySelector('.companion-fx-lupa .companion-lupa-lens')).not.toBeNull()
  })

  it('lleva el accesorio de temporada salvo en pequeño o si se ha quitado', () => {
    const { container, rerender } = render(<Companion state="calm" season="halloween" />)
    expect(svg(container).dataset.season).toBe('halloween')
    expect(container.querySelector('[data-hat="halloween"]')).not.toBeNull()
    rerender(<Companion state="calm" size={24} season="christmas" />)
    expect(container.querySelector('.companion-hat')).toBeNull()
    rerender(<Companion state="calm" season={null} />)
    expect(container.querySelector('.companion-hat')).toBeNull()
  })

  it('sin `season`, toma el de la fecha y respeta la preferencia', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-12-24T12:00:00'))
    const { container, rerender } = render(<Companion state="calm" />)
    expect(container.querySelector('[data-hat="christmas"]')).not.toBeNull()
    act(() => setCompanionPrefs({ seasonal: false }))
    rerender(<Companion state="calm" />)
    expect(container.querySelector('.companion-hat')).toBeNull()
  })

  it('quieta en listas', () => {
    const { container } = render(<Companion state="thinking" still />)
    expect(svg(container).hasAttribute('data-still')).toBe(true)
  })
})
