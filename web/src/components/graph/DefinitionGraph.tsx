import { Bot, CircleAlert, CircleHelp, Info, TriangleAlert, Workflow } from 'lucide-react'
import { useMemo } from 'react'
import type { Problem, StageDefinition, WorkflowModel } from '../../api'
import { cn } from '../../lib/cn'
import { stageDetail, stageHints, toGraphStages, TYPE_ICON } from './definitionNodes'
import {
  cycleEdges,
  missingDependencies,
  problemBadge,
  problemsByStage,
} from './definitionProblems'
import WorkflowGraph, { type GraphContent } from './WorkflowGraph'

const BADGE_ICON = { ERROR: CircleAlert, UNSUPPORTED: Info, WARNING: TriangleAlert }

/** Lo más grave de la fase, con icono y texto (el color no es la única señal). */
function ProblemBadge({ problems }: { problems: Problem[] }) {
  const badge = problemBadge(problems)
  if (!badge) return null
  const Icon = BADGE_ICON[badge.severity]
  return (
    <div
      className={cn(
        'graph-problems graph-problems-pill',
        `graph-problems-${badge.severity.toLowerCase()}`,
      )}
      title={problems.map((p) => p.message).join('\n')}
    >
      <Icon size={13} aria-hidden="true" />
      <span>{badge.label}</span>
    </div>
  )
}

function StageNode({
  stage,
  index,
  problems,
}: {
  stage: StageDefinition
  index: number
  problems: Problem[]
}) {
  const Icon = TYPE_ICON[stage.type] ?? Workflow
  const detail = stageDetail(stage)
  const hints = stageHints(stage)
  return (
    <div className="graph-box">
      <div className="graph-title">
        <Icon size={16} aria-hidden="true" />
        <span className="graph-name" title={stage.name ?? stage.id}>
          <span className="graph-index">{index + 1}.</span> {stage.name ?? stage.id}
        </span>
        <span className="tag">{stage.type}</span>
      </div>
      {(detail || hints.length > 0) && (
        <div className="graph-detail" title={[detail, ...hints].filter(Boolean).join(' · ')}>
          {[detail, ...hints].filter(Boolean).join(' · ')}
        </div>
      )}
      <ProblemBadge problems={problems} />
    </div>
  )
}

/** Una fase que se nombra en `dependsOn` pero no existe: discontinua, con su nombre. */
function GhostNode({ id, from }: { id: string; from: string[] }) {
  return (
    <div className="graph-box">
      <div className="graph-title">
        <CircleHelp size={16} aria-hidden="true" />
        <span className="graph-name" title={id}>
          {id}
        </span>
      </div>
      <div className="graph-problems graph-problems-error">
        <CircleAlert size={13} aria-hidden="true" />
        <span>No existe</span>
      </div>
      <span className="visually-hidden">: la nombra {from.join(', ')}</span>
    </div>
  )
}

/**
 * El grafo de la definición: qué hace cada fase y de cuáles depende, sin estado. Con `problems`
 * (los de la validación de `source`), cada fase lleva una insignia con los suyos, una dependencia
 * que no existe sale como fase fantasma y las flechas de un ciclo van en rojo.
 */
export default function DefinitionGraph({
  definition,
  problems = [],
  source = '',
}: {
  definition: WorkflowModel
  problems?: Problem[]
  source?: string
}) {
  const missing = useMemo(() => missingDependencies(definition.stages), [definition])
  const stages = useMemo(
    () => [
      ...toGraphStages(definition),
      ...[...missing.keys()].map((id) => ({ id, dependsOn: [], children: [], phase: null })),
    ],
    [definition, missing],
  )
  const byStage = useMemo(() => problemsByStage(source, problems), [source, problems])
  const content = useMemo<GraphContent>(() => {
    const byId = new Map(definition.stages.map((s, i) => [s.id, { stage: s, index: i }]))
    const cycles = cycleEdges(definition.stages)
    return {
      stage: (id) => {
        const found = byId.get(id)
        if (!found) return <GhostNode id={id} from={missing.get(id) ?? []} />
        return (
          <StageNode stage={found.stage} index={found.index} problems={byStage.get(id) ?? []} />
        )
      },
      child: (stageId, childId) => {
        const child = byId.get(stageId)?.stage.children.find((c) => c.id === childId)
        return (
          <div className="graph-box">
            <div className="graph-title">
              <Bot size={14} aria-hidden="true" />
              <span className="graph-name" title={childId}>
                {childId}
              </span>
            </div>
            {child && (child.agent !== childId || child.retry) && (
              <div className="graph-detail">
                {[
                  child.agent !== childId && child.agent,
                  child.retry && `hasta ${child.retry.maxAttempts} intentos`,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </div>
            )}
          </div>
        )
      },
      stageClass: (id) => {
        if (!byId.has(id)) return 'graph-ghost'
        const badge = problemBadge(byStage.get(id) ?? [])
        return cn(
          `graph-type-${byId.get(id)?.stage.type ?? 'unknown'}`,
          badge?.severity === 'ERROR' && 'graph-has-errors',
        )
      },
      edgeClass: (from, to) =>
        missing.has(from)
          ? 'graph-edge-missing'
          : cycles.has(`${from}->${to}`)
            ? 'graph-edge-cycle'
            : undefined,
    }
  }, [definition, missing, byStage])

  return (
    <WorkflowGraph
      stages={stages}
      content={content}
      label={`Grafo de ${definition.name ?? definition.key}`}
    />
  )
}
