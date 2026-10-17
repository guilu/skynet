import type { StageRun } from '../../api'
import { formatCost, providerName } from '../../format'
import { attemptOf } from '../run/stageNotes'
import type { GraphStage } from './layout'

/** Los hijos de una `parallel`: los del YAML y, si la versión ya no se lee, los de sus agentes. */
function childrenOf(stage: StageRun): string[] {
  if (stage.type !== 'parallel') return []
  if (stage.children.length > 0) return stage.children
  return [...new Set(stage.agents.map((a) => a.childKey).filter((k): k is string => k != null))]
}

export function toRunGraphStages(stages: StageRun[]): GraphStage[] {
  return stages.map((s) => ({
    id: s.stageKey,
    dependsOn: s.dependsOn,
    children: childrenOf(s),
    phase: s.phase,
  }))
}

/** Quién la hace: el agente con nombre, el proveedor del último agente o nada. */
function whoOf(stage: StageRun): string | null {
  if (stage.type === 'parallel' || stage.type === 'command') return null
  if (stage.agent) return stage.agent
  const last = stage.agents.at(-1)
  return last && last.provider !== 'command' && last.provider !== 'verification'
    ? providerName(last.provider)
    : null
}

/** Lo que cuesta el intento: la suma de sus agentes, si alguno informó. */
function costOf(stage: StageRun): number | null {
  const costs = stage.agents.map((a) => a.costUsd).filter((c): c is number => c != null)
  return costs.length > 0 ? costs.reduce((a, b) => a + b, 0) : null
}

/** La línea de debajo del nombre: agente, coste e intento. */
export function stageFacts(stage: StageRun): string[] {
  const facts: string[] = []
  const who = whoOf(stage)
  if (who) facts.push(who)
  const cost = costOf(stage)
  if (cost != null && cost > 0) facts.push(formatCost(cost))
  if (stage.attempt > 1) facts.push(`intento ${stage.attempt}${attemptOf(stage)}`)
  return facts
}
