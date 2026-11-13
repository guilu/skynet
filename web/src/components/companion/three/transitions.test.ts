import { describe, expect, it } from 'vitest'
import type { CompanionState } from '../state'
import type { StateChange } from './scene'
import { DemoRunner, DEMOS, PROGRESS_STEP_MS, STEP_GAP_MS, type DemoStage } from './transitions'

/** Una escena de mentira: está ocupada hasta que el test dice que acabó. */
class FakeStage implements DemoStage {
  current: CompanionState = 'calm'
  busy = false
  progress: [number, number] | null = null
  history: { state: CompanionState; message?: string }[] = []
  get progressShown() {
    return this.current === 'running' && this.progress !== null && !this.busy
  }
  setState(state: CompanionState, change: StateChange = {}) {
    this.current = state
    this.busy = true
    this.history.push({ state, message: change.message })
  }
  setProgress(done: number | null, total = 0) {
    this.progress = done === null ? null : [done, total]
  }
}

/** Avanza la demo hasta el final, dejando acabar cada gesto y letrero. */
function play(runner: DemoRunner, stage: FakeStage, retry = false) {
  let now = 0
  for (let i = 0; i < 400 && (runner.running || stage.busy); i++) {
    now += 100
    stage.busy = false
    if (retry && runner.waitingRetry) runner.retry()
    runner.tick(now)
  }
}

describe('demos de transiciones', () => {
  it('«Simular tarea» va de Pensando a Éxito pasando por Ejecutando y Revisando', () => {
    const stage = new FakeStage()
    const runner = new DemoRunner(stage)
    runner.toggle('task')
    play(runner, stage)
    expect(stage.history.map((h) => h.state)).toEqual(DEMOS.task.steps.map((s) => s.state))
    expect(runner.running).toBe(false)
  })

  it('Ejecutando enseña el contador 1/5 … 5/5 antes de seguir', () => {
    const stage = new FakeStage()
    const runner = new DemoRunner(stage)
    runner.toggle('task')
    stage.busy = false
    runner.tick(0)
    runner.tick(STEP_GAP_MS)
    expect(stage.current).toBe('running')
    expect(stage.progress).toEqual([1, 5])
    stage.busy = false
    const seen: string[] = []
    for (let now = 1000; stage.progress; now += PROGRESS_STEP_MS) {
      seen.push(stage.progress.join('/'))
      runner.tick(now)
    }
    expect(seen).toEqual(['1/5', '2/5', '3/5', '4/5', '5/5'])
  })

  it('la recuperación se para en Te necesita hasta «Reintentar» y reintenta con letrero', () => {
    const stage = new FakeStage()
    const runner = new DemoRunner(stage)
    runner.toggle('recovery')
    play(runner, stage)
    expect(stage.current).toBe('waiting')
    expect(runner.waitingRetry).toBe(true)
    play(runner, stage, true)
    const states = stage.history.map((h) => h.state)
    expect(states).toEqual(['running', 'error', 'waiting', 'running', 'success'])
    expect(stage.history[3].message).toBe('REINTENTO 1/2')
  })

  it('cancelar en Ejecutando pasa por Cancelado y vuelve a Reposo', () => {
    const stage = new FakeStage()
    const runner = new DemoRunner(stage)
    runner.toggle('task')
    expect(runner.canCancel).toBe(false)
    stage.busy = false
    runner.tick(0)
    runner.tick(STEP_GAP_MS)
    expect(runner.canCancel).toBe(true)
    runner.cancel()
    expect(stage.current).toBe('cancelled')
    expect(runner.running).toBe(false)
    runner.tick(5000)
    expect(stage.current).toBe('cancelled')
    stage.busy = false
    runner.tick(5100)
    expect(stage.current).toBe('calm')
  })

  it('pulsar otra vez el botón para la demo', () => {
    const stage = new FakeStage()
    const runner = new DemoRunner(stage)
    runner.toggle('task')
    runner.toggle('task')
    expect(runner.running).toBe(false)
    expect(stage.progress).toBeNull()
  })
})
