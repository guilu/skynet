import type { Run } from '../../api'
import { isTerminal } from '../../format'
import { latestAttempts } from '../run/stageNotes'
import { Companion } from './Companion'
import type { Gesture } from './gesture'
import { bandText } from './peek'
import { runLive } from './runLive'
import { SkynyLive } from './SkynyLive'
import { runExpression } from './state'

/** Skyny en 3D (SV-B) y el 2D de respaldo, que mide lo de siempre. */
const SIZE_3D = 120
const SIZE_2D = 56

/**
 * Bajo la línea de fases, la mascota cuenta qué pasa ahora en la ejecución (plan, §5): quién
 * trabaja y en qué, y debajo la fase, el tiempo y el coste. Desde SV-B, Skyny va en 3D y en vivo:
 * cambia de estado con sus gestos y letreros, cuenta por qué fase va y avisa de los reintentos.
 * Sin WebGL o con movimiento reducido, el 2D, que anima solo mientras está en marcha.
 */
export function RunBand({
  run,
  now,
  gesture,
}: {
  run: Run
  now: number
  /** Un gesto de una vez (el salto al terminar bien mientras la miras); anima aunque haya acabado. */
  gesture?: Gesture | null
}) {
  const stages = latestAttempts(run)
  const state = runExpression(run, stages, now)
  const { title, sub } = bandText(run, stages, now)
  const done = isTerminal(run.status)
  const { progress, message } = runLive(run, stages)
  return (
    <section className="card run-band" data-state={state} aria-label="Qué pasa ahora">
      <SkynyLive
        state={state}
        size={SIZE_3D}
        progress={progress}
        message={message}
        fallback={
          <Companion state={state} size={SIZE_2D} still={done && !gesture} gesture={gesture} />
        }
      />
      <div className="run-band-text">
        <strong>{title}</strong>
        <span className="muted">{sub}</span>
      </div>
    </section>
  )
}
