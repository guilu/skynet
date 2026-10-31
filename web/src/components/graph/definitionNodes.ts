import {
  Bot,
  GitBranch,
  GitPullRequest,
  Hand,
  ListChecks,
  Split,
  SquareTerminal,
  type LucideIcon,
} from 'lucide-react'
import type { StageDefinition, WorkflowModel } from '../../api'
import { formatSeconds } from '../run/stageNotes'
import type { GraphStage } from './layout'

/** Un icono por tipo de fase, el mismo en el grafo del workflow y en el de la ejecución. */
export const TYPE_ICON: Record<string, LucideIcon> = {
  agent: Bot,
  command: SquareTerminal,
  parallel: Split,
  conditional: GitBranch,
  verification: ListChecks,
  'human-approval': Hand,
  'github-pr': GitPullRequest,
}

/** Lo que hace la fase, en una línea corta: el agente, el comando, la condición… */
export function stageDetail(stage: StageDefinition): string {
  switch (stage.type) {
    case 'agent':
      return stage.agent ?? 'Agente del repositorio'
    case 'command':
      return stage.command ?? ''
    case 'parallel':
      return `${stage.children.length} a la vez`
    case 'conditional':
      return stage.condition ? `Si ${stage.condition}` : ''
    case 'verification':
      return stage.checks.length === 1 ? '1 comprobación' : `${stage.checks.length} comprobaciones`
    case 'human-approval':
      return stage.approval ? `Aprobar: ${stage.approval.action}` : 'Aprobación'
    case 'github-pr':
      return 'Subir la rama y abrir la PR'
    default:
      return ''
  }
}

/** Reintentos, límite de tiempo y condición de una fase que no es `conditional`. */
export function stageHints(stage: StageDefinition): string[] {
  const hints: string[] = []
  if (stage.retry) hints.push(`hasta ${stage.retry.maxAttempts} intentos`)
  if (stage.timeoutSeconds != null) hints.push(`máx. ${formatSeconds(stage.timeoutSeconds)}`)
  if (stage.condition && stage.type !== 'conditional') hints.push(`si ${stage.condition}`)
  return hints
}

export function toGraphStages(definition: WorkflowModel): GraphStage[] {
  return definition.stages.map((s) => ({
    id: s.id,
    dependsOn: s.dependsOn,
    children: s.type === 'parallel' ? s.children.map((c) => c.id) : [],
    phase: s.phase,
  }))
}
