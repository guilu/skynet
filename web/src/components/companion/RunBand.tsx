import type { Run } from '../../api'
import { isTerminal } from '../../format'
import { latestAttempts } from '../run/stageNotes'
import { Companion } from './Companion'
import { bandText } from './peek'
import { runExpression } from './state'

/**
 * Bajo la línea de fases, la mascota cuenta qué pasa ahora en la ejecución (plan, §5): quién
 * trabaja y en qué, y debajo la fase, el tiempo y el coste. Anima solo mientras está en marcha.
 */
export function RunBand({ run, now }: { run: Run; now: number }) {
  const stages = latestAttempts(run)
  const state = runExpression(run, stages, now)
  const { title, sub } = bandText(run, stages, now)
  const done = isTerminal(run.status)
  return (
    <section className="card run-band" data-state={state} aria-label="Qué pasa ahora">
      <Companion state={state} size={56} still={done} />
      <div className="run-band-text">
        <strong>{title}</strong>
        <span className="muted">{sub}</span>
      </div>
    </section>
  )
}
