import { FlaskConical } from 'lucide-react'
import { Pill } from './Pill'

/** Aviso de que un CLI de agentes es un simulador de pruebas (fake-claude, fake-codex…). */
export const SIMULATED_HINT =
  'CLI simulado: reproduce una sesión grabada y no ejecuta el agente real'

/** Píldora «Simulado» junto a un proveedor o un agente que usa un CLI simulado (AR-A). */
export function SimulatedPill() {
  return (
    <span className="simulated" title={SIMULATED_HINT}>
      <Pill tone="warn" icon={FlaskConical}>
        Simulado
        <span className="visually-hidden">: {SIMULATED_HINT}</span>
      </Pill>
    </span>
  )
}
