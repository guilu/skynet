import { describe, expect, it } from 'vitest'
import { runLive } from './runLive'

const stage = (id: string, attempt = 1, maxAttempts?: number) => ({
  id,
  attempt,
  retry: maxAttempts ? { maxAttempts, on: [], delaySeconds: 0 } : null,
})

describe('Skyny en la banda de la ejecución', () => {
  it('con varias fases, cuenta por cuál va', () => {
    const run = { status: 'RUNNING' as const, currentStageRunId: 'b' }
    expect(runLive(run, [stage('a'), stage('b'), stage('c')]).progress).toEqual([2, 3])
  })

  it('con una sola fase o sin estar en marcha, no hay contador', () => {
    expect(runLive({ status: 'RUNNING', currentStageRunId: 'a' }, [stage('a')]).progress).toBeNull()
    expect(
      runLive({ status: 'SUCCEEDED', currentStageRunId: 'a' }, [stage('a'), stage('b')]).progress,
    ).toBeNull()
  })

  it('un reintento lleva su letrero', () => {
    const run = { status: 'RUNNING' as const, currentStageRunId: 'a' }
    expect(runLive(run, [stage('a', 2, 3)]).message).toBe('REINTENTO 1/2')
    expect(runLive(run, [stage('a', 3)]).message).toBe('INTENTO 3')
    expect(runLive(run, [stage('a')]).message).toBeUndefined()
  })
})
