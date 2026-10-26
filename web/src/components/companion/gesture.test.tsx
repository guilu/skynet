import { act, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Companion } from './Companion'
import { CompanionPeek } from './CompanionPeek'
import { GESTURE_MS, QUIP_MS, SKYNY_QUIPS, resetQuips, useGesture } from './gesture'
import { lookOffset } from './look'
import { useCelebration, CELEBRATION_MS } from './useCelebration'
import type { RunStatus } from '../../api'

const svg = (container: HTMLElement) => container.querySelector<SVGSVGElement>('svg.companion')!

describe('Interacciones de Skyny (SY-C)', () => {
  beforeEach(() => resetQuips())
  afterEach(() => vi.useRealTimers())

  it('un gesto se quita solo al terminar', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useGesture())
    expect(result.current[0]).toBeNull()
    act(() => result.current[1]('yawn'))
    expect(result.current[0]).toBe('yawn')
    act(() => vi.advanceTimersByTime(GESTURE_MS.yawn))
    expect(result.current[0]).toBeNull()
  })

  it('cada gesto pone sus ojos encima de la expresión', () => {
    const { container, rerender } = render(<Companion state="calm" gesture="hop" />)
    expect(svg(container).dataset.gesture).toBe('hop')
    expect(svg(container).classList).toContain('companion-gesture')
    // Se ríe: los ojos en arco de Éxito, aunque siga en Reposo.
    expect(svg(container).dataset.state).toBe('calm')
    expect(container.querySelector('.companion-eye-line')).not.toBeNull()
    rerender(<Companion state="calm" gesture="yawn" />)
    expect(container.querySelector('.companion-eye-line')?.getAttribute('d')).toContain('Q51 69')
    rerender(<Companion state="calm" />)
    expect(container.querySelector('.companion-eye-line')).toBeNull()
    expect(svg(container).dataset.gesture).toBeUndefined()
  })

  it('se tapa los ojos o mira de reojo', () => {
    const { container, rerender } = render(<Companion state="calm" pose="cover" />)
    expect(svg(container).dataset.pose).toBe('cover')
    rerender(<Companion state="calm" pose="peek" />)
    expect(svg(container).dataset.pose).toBe('peek')
    rerender(<Companion state="calm" />)
    expect(svg(container).dataset.pose).toBeUndefined()
  })

  it('la gotita de sudor sale en mediano y grande, no en pequeño', () => {
    const { container, rerender } = render(<Companion state="paused" size={48} sweat />)
    expect(container.querySelector('.companion-fx-sweat')).not.toBeNull()
    rerender(<Companion state="paused" size={24} sweat />)
    expect(container.querySelector('.companion-fx-sweat')).toBeNull()
  })

  it('solo sigue al cursor en grande', () => {
    const { container, rerender } = render(<Companion state="calm" size={96} look />)
    expect(svg(container).dataset.look).toBe('true')
    rerender(<Companion state="calm" size={48} look />)
    expect(svg(container).dataset.look).toBeUndefined()
  })

  it('los ojos van hacia el punto, más cuanto más lejos, hasta 4', () => {
    const box = { left: 0, top: 0, width: 176, height: 154 }
    // El visor cae en (84, 86) de esta caja.
    expect(lookOffset(box, 84, 86)).toEqual([0, 0])
    expect(lookOffset(box, 1084, 86)).toEqual([4, 0])
    expect(lookOffset(box, 84, -914)).toEqual([0, -4])
    expect(lookOffset(box, 184, 86)).toEqual([2, 0])
  })

  it('al pulsarla en la cabecera salta y dice frases por turnos', () => {
    vi.useFakeTimers()
    const { container } = render(<CompanionPeek state="calm" text="Todo al día" />)
    expect(screen.getByText('Todo al día')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Hablar con Skyny' }))
    expect(screen.getByText(SKYNY_QUIPS[0])).toBeInTheDocument()
    expect(svg(container).dataset.gesture).toBe('hop')
    fireEvent.click(screen.getByRole('button', { name: 'Hablar con Skyny' }))
    expect(screen.getByText(SKYNY_QUIPS[1])).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(QUIP_MS))
    expect(screen.getByText('Todo al día')).toBeInTheDocument()
    expect(svg(container).dataset.gesture).toBeUndefined()
  })

  it('celebra solo cuando la ejecución abierta pasa a terminada bien', () => {
    vi.useFakeTimers()
    const { result, rerender } = renderHook(({ status }) => useCelebration(status), {
      initialProps: { status: undefined as RunStatus | undefined },
    })
    // Abrir una ya terminada no celebra.
    rerender({ status: 'SUCCEEDED' })
    expect(result.current).toBe(false)
    rerender({ status: 'RUNNING' })
    rerender({ status: 'SUCCEEDED' })
    expect(result.current).toBe(true)
    act(() => vi.advanceTimersByTime(CELEBRATION_MS))
    expect(result.current).toBe(false)
    rerender({ status: 'RUNNING' })
    rerender({ status: 'FAILED' })
    expect(result.current).toBe(false)
  })
})
