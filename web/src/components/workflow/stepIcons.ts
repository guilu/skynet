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
