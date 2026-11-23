import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CURTAIN_SKYNY_EXIT_MS,
  CURTAIN_UP_MS,
  FLOAT_EXIT_MS,
  useCurtainSequence,
} from './curtainSequence'

afterEach(() => vi.useRealTimers())

describe('useCurtainSequence', () => {
  it('al abrir, primero se va el Skyny de la web y después baja la cortina', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useCurtainSequence({ floating: true, curtain3D: true }))
    act(() => result.current.request(true))
    expect(result.current).toMatchObject({ phase: 'leaving', open: false, away: true })
    act(() => vi.advanceTimersByTime(FLOAT_EXIT_MS - 1))
    expect(result.current.open).toBe(false)
    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toMatchObject({ phase: 'open', open: true, away: true })
  })

  it('al cerrar, se va el de la cortina, sube la cortina y vuelve el de la web', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useCurtainSequence({ floating: true, curtain3D: true }))
    act(() => result.current.toggle())
    act(() => vi.advanceTimersByTime(FLOAT_EXIT_MS))
    act(() => result.current.toggle())
    expect(result.current).toMatchObject({ phase: 'returning', open: true, leaving: true })
    act(() => vi.advanceTimersByTime(CURTAIN_SKYNY_EXIT_MS))
    expect(result.current).toMatchObject({ phase: 'raising', open: false, away: true })
    act(() => vi.advanceTimersByTime(CURTAIN_UP_MS))
    expect(result.current).toMatchObject({ phase: 'closed', open: false, away: false })
  })

  it('sin Skyny flotante, la cortina baja sin esperar', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useCurtainSequence({ floating: false, curtain3D: false }))
    act(() => result.current.request(true))
    act(() => vi.advanceTimersByTime(0))
    expect(result.current.phase).toBe('open')
  })

  it('a media salida del de la web, cerrar lo deja como estaba', () => {
    const { result } = renderHook(() => useCurtainSequence({ floating: true, curtain3D: true }))
    act(() => result.current.request(true))
    act(() => result.current.request(false))
    expect(result.current.phase).toBe('closed')
  })
})
