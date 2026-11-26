// Transiciones de Skyny 3D (estudio v4.3): secuencias de estados que cuentan una tarea de principio
// a fin. Las usan los botones de demo del estudio («Simular asistencia», «Simular tarea»,
// «Simular recuperación», «Cancelar», «Reintentar»).
import type { StateChange } from './scene'
import type { SkynyState } from './states'

/** El letrero de un reintento: «REINTENTO 1/2». */
export const retryMessage = (attempt: number, max: number) => `REINTENTO ${attempt}/${max}`

export interface DemoStep extends StateChange {
  state: SkynyState
  /** Pasos del contador de Ejecutando (1/5 … 5/5). */
  steps?: number
  /** Se para aquí hasta que se pulsa «Reintentar». */
  waitRetry?: boolean
}

export type DemoKind = 'assistance' | 'task' | 'recovery'

export const DEMOS: Record<DemoKind, { label: string; steps: DemoStep[] }> = {
  assistance: {
    label: 'Simular asistencia',
    steps: [
      { state: 'listening' },
      { state: 'recalling' },
      { state: 'compacting' },
      { state: 'thinking' },
      { state: 'external-wait' },
      { state: 'streaming' },
      { state: 'check-result' },
    ],
  },
  task: {
    label: 'Simular tarea',
    steps: [
      { state: 'syncing' },
      { state: 'context-indexing' },
      { state: 'routing' },
      { state: 'escalating' },
      { state: 'dry-run' },
      { state: 'sandbox' },
      { state: 'thinking' },
      { state: 'planning' },
      { state: 'handover' },
      { state: 'delegating' },
      { state: 'coding', steps: 4 },
      { state: 'testing' },
      { state: 'benchmarking' },
      { state: 'auditing' },
      { state: 'pr-open' },
      { state: 'merging' },
      { state: 'deploying' },
      { state: 'learning' },
      { state: 'celebrating' },
    ],
  },
  recovery: {
    label: 'Simular recuperación',
    steps: [
      { state: 'running', steps: 5 },
      { state: 'guardrail' },
      { state: 'error' },
      { state: 'conflict' },
      { state: 'rolling-back' },
      { state: 'healing' },
      { state: 'degraded' },
      { state: 'rate-limited' },
      { state: 'testing' },
      { state: 'waiting', waitRetry: true },
      { state: 'running', message: retryMessage(1, 2), steps: 5 },
      { state: 'success' },
    ],
  },
}

/** Pausa entre un paso y el siguiente, y lo que dura cada paso del contador. */
export const STEP_GAP_MS = 350
export const PROGRESS_STEP_MS = 550

/** Lo que el guion necesita de la escena (así se prueba sin WebGL). */
export interface DemoStage {
  readonly current: SkynyState
  readonly busy: boolean
  readonly progressShown: boolean
  setState(state: SkynyState, change?: StateChange): void
  setProgress(done: number | null, total?: number): void
}

/**
 * Lleva una demo paso a paso: cada estado espera a que Skyny acabe su gesto y su letrero (y el
 * contador, en Ejecutando) antes de pasar al siguiente. Se mueve llamando a `tick` en cada
 * fotograma.
 */
export class DemoRunner {
  kind: DemoKind | null = null
  private step = 0
  private nextAt: number | null = null
  private progress: { done: number; total: number; at: number } | null = null
  /** Cancelado: vuelve a Reposo cuando acabe el letrero. */
  private cancelled = false

  private readonly stage: DemoStage

  constructor(stage: DemoStage) {
    this.stage = stage
  }

  get running() {
    return this.kind !== null
  }

  /** Se puede cancelar: hay una demo y Skyny está en Ejecutando. */
  get canCancel() {
    return this.kind !== null && this.stage.current === 'running'
  }

  /** Espera a «Reintentar»: la recuperación, en Te necesita y con el letrero acabado. */
  get waitingRetry() {
    const step = this.kind ? DEMOS[this.kind].steps[this.step] : undefined
    return !!step?.waitRetry && !this.stage.busy
  }

  /** Empieza la demo `kind`, o la para si ya estaba en marcha. */
  toggle(kind: DemoKind) {
    const was = this.kind
    this.stop()
    if (was === kind) return
    this.kind = kind
    this.step = 0
    this.go()
  }

  stop() {
    this.kind = null
    this.nextAt = null
    this.progress = null
    this.stage.setProgress(null)
  }

  /** Cancela en Ejecutando: Skyny recoge las líneas, escribe «CANCELADO» y vuelve a Reposo. */
  cancel() {
    if (!this.canCancel) return
    this.stop()
    this.cancelled = true
    this.stage.setState('cancelled')
  }

  retry() {
    if (!this.waitingRetry) return
    this.step++
    this.go()
  }

  tick(now: number) {
    if (this.cancelled) {
      if (!this.stage.busy) {
        this.cancelled = false
        this.stage.setState('calm')
      }
      return
    }
    if (!this.kind) return
    const step = DEMOS[this.kind].steps[this.step]
    // El contador avanza mientras se ve; al pasar del último, se quita.
    if (this.progress && this.stage.progressShown) {
      if (now - this.progress.at >= PROGRESS_STEP_MS) {
        this.progress.done++
        this.progress.at = now
        if (this.progress.done > this.progress.total) {
          this.progress = null
          this.stage.setProgress(null)
        } else this.stage.setProgress(this.progress.done, this.progress.total)
      }
      return
    }
    if (this.progress) this.progress.at = now
    if (this.stage.busy || step.waitRetry) return
    this.nextAt ??= now + STEP_GAP_MS
    if (now < this.nextAt) return
    this.step++
    if (this.step >= DEMOS[this.kind].steps.length) this.stop()
    else this.go()
  }

  private go() {
    const step = DEMOS[this.kind!].steps[this.step]
    this.nextAt = null
    this.progress = step.steps ? { done: 1, total: step.steps, at: 0 } : null
    this.stage.setProgress(this.progress ? 1 : null, step.steps)
    this.stage.setState(step.state, { message: step.message })
  }
}
