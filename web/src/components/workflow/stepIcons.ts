import {
  Bot,
  Circle,
  GitPullRequest,
  Hand,
  Scale,
  Terminal,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { providerInfo } from './overview'
import type { Step } from './steps'

/** El icono de cada clase de paso, en el resumen y en el editor. */
export const STEP_ICON: Record<Step['icon'], LucideIcon> = {
  agent: Bot,
  approval: Hand,
  judge: Scale,
  github: GitPullRequest,
  command: Terminal,
  parallel: Users,
  other: Circle,
}

/** Quién hace un paso, en una línea: «Claude Code · arquitecto», o su tipo si no lleva agente. */
export function stepWho(step: Step): string {
  const w = step.workers[0]
  if (!w) return step.kind
  return `${providerInfo(w.provider).name}${w.agent ? ` · ${w.agent}` : ''}`
}
