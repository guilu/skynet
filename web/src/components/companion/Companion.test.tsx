import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Companion, SWAP_MS } from './Companion'

const svg = (container: HTMLElement) => container.querySelector<SVGSVGElement>('svg.companion')!

describe('Companion', () => {
  afterEach(() => vi.useRealTimers())

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

  it('en pequeño quita efectos y sombra', () => {
    const { container: big } = render(<Companion state="success" size={64} />)
    expect(big.querySelector('.companion-fx-confetti')).not.toBeNull()
    expect(big.querySelector('.companion-shade')).not.toBeNull()
    const { container: small } = render(<Companion state="success" size={24} />)
    expect(small.querySelector('.companion-fx')).toBeNull()
    expect(small.querySelector('.companion-shade')).toBeNull()
    expect(svg(small).hasAttribute('data-small')).toBe(true)
  })

  it('quieta en listas', () => {
    const { container } = render(<Companion state="thinking" still />)
    expect(svg(container).hasAttribute('data-still')).toBe(true)
  })
})
