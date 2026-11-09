import type { Risk } from '../../api'
import type { StatusTone } from '../../format'
import { Pill } from '../ui/Pill'
import { riskTitle } from './riskFormat'

const TONE: Record<Risk['tier'], StatusTone> = { 0: 'ok', 1: 'neutral', 2: 'warn' }

/**
 * El nivel de riesgo de un cambio (AU-A) como píldora: 0 en verde, 1 neutro y 2 en ámbar, siempre
 * con su número. Al pasar por encima, el resumen y los ficheros que lo fijaron.
 */
export function RiskPill({ risk }: { risk: Risk }) {
  return (
    <span className="risk-pill" title={riskTitle(risk)} aria-label={risk.summary}>
      <Pill tone={TONE[risk.tier]} icon={risk.tier === 2 ? 'alert-warn' : 'checks'}>
        Riesgo {risk.tier}
      </Pill>
    </span>
  )
}
