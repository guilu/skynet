import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearScouter, sayOnScouter, SCOUTER_MS, useScouterText } from './scouter'

afterEach(() => {
  clearScouter()
  vi.useRealTimers()
})

describe('scouter', () => {
  it('enseña lo que dice Skyny y vuelve al estado pasado un rato', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useScouterText())
    expect(result.current).toBe('')
    act(() => sayOnScouter('  ¡Gracias! Sigo con ello. '))
    expect(result.current).toBe('¡Gracias! Sigo con ello.')
    act(() => vi.advanceTimersByTime(SCOUTER_MS - 1))
    expect(result.current).toBe('¡Gracias! Sigo con ello.')
    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toBe('')
  })

  it('un texto nuevo sustituye al anterior y alarga el tiempo', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useScouterText())
    act(() => sayOnScouter('uno'))
    act(() => vi.advanceTimersByTime(SCOUTER_MS - 100))
    act(() => sayOnScouter('dos'))
    act(() => vi.advanceTimersByTime(200))
    expect(result.current).toBe('dos')
  })
})
