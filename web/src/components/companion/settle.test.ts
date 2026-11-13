import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DWELL_MS, settle, useSettledState } from './settle'

describe('permanencia mínima de Skyny', () => {
  it('cambia enseguida si ya lleva bastante en el estado', () => {
    expect(settle('thinking', 0, 'running', DWELL_MS)).toEqual({ state: 'running', wait: 0 })
  })

  it('espera lo que falta si acaba de cambiar', () => {
    expect(settle('thinking', 1000, 'running', 1500)).toEqual({ state: 'thinking', wait: 700 })
  })

  it('lo urgente no espera', () => {
    for (const urgent of ['waiting', 'error', 'offline', 'cancelled'] as const)
      expect(settle('running', 1000, urgent, 1001).state).toBe(urgent)
  })
})

describe('useSettledState', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] }))
  afterEach(() => vi.useRealTimers())

  it('junta los cambios rápidos en el último', () => {
    vi.advanceTimersByTime(5000)
    const { result, rerender } = renderHook(({ s }) => useSettledState(s), {
      initialProps: { s: 'thinking' as const } as { s: 'thinking' | 'running' | 'reviewing' },
    })
    rerender({ s: 'running' })
    expect(result.current).toBe('running')
    rerender({ s: 'thinking' })
    rerender({ s: 'reviewing' })
    expect(result.current).toBe('running')
    act(() => vi.advanceTimersByTime(DWELL_MS))
    expect(result.current).toBe('reviewing')
  })
})
