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
import WorkflowGraph, { type GraphContent, type GraphEditing } from './WorkflowGraph'

/** Lo elegido en el grafo al editarlo: una fase o una flecha (`to` depende de `from`). */
export type GraphSelection = { stage: string } | { from: string; to: string } | null

const BADGE_ICON = { ERROR: CircleAlert, UNSUPPORTED: Info, WARNING: TriangleAlert }

/** Lo más grave de la fase, con icono y texto (el color no es la única señal). */
function ProblemBadge({ problems }: { problems: Problem[] }) {
  const badge = problemBadge(problems)
  if (!badge) return null
  const Icon = BADGE_ICON[badge.severity]
  return (
    <span
      className={cn(
        'graph-problems graph-problems-pill',
        `graph-problems-${badge.severity.toLowerCase()}`,
      )}
      title={problems.map((p) => p.message).join('\n')}
    >
      <Icon size={13} aria-hidden="true" />
      <span>{badge.label}</span>
    </span>
  )
}

function StageNode({
  stage,
  index,
  problems,
  selected,
  onSelect,
}: {
  stage: StageDefinition
  index: number
  problems: Problem[]
  selected?: boolean
  /** Al editar, la caja es un botón que elige la fase. */
  onSelect?: () => void
}) {
  const Icon = TYPE_ICON[stage.type] ?? Workflow
  const detail = stageDetail(stage)
  const hints = stageHints(stage)
  const inner = (
    <>
      <span className="graph-title">
        <Icon size={16} aria-hidden="true" />
        <span className="graph-name" title={stage.name ?? stage.id}>
          <span className="graph-index">{index + 1}.</span> {stage.name ?? stage.id}
        </span>
        <span className="tag">{stage.type}</span>
      </span>
      {(detail || hints.length > 0) && (
        <span className="graph-detail" title={[detail, ...hints].filter(Boolean).join(' · ')}>
          {[detail, ...hints].filter(Boolean).join(' · ')}
        </span>
      )}
      <ProblemBadge problems={problems} />
    </>
  )
  if (!onSelect) return <div className="graph-box">{inner}</div>
  return (
    <button
      type="button"
      className="graph-box graph-button"
      aria-pressed={selected}
      onClick={onSelect}
    >
      {inner}
    </button>
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
  selection = null,
  onSelect,
  onConnect,
}: {
  definition: WorkflowModel
  problems?: Problem[]
  source?: string
  /** Al editar (W8-B): lo elegido, cómo se elige y qué pasa al unir dos fases arrastrando. */
  selection?: GraphSelection
  onSelect?: (selection: GraphSelection) => void
  onConnect?: (from: string, to: string) => void
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
  const selected = selection && 'stage' in selection ? selection.stage : null
  const edge = selection && 'from' in selection ? selection : null
  const content = useMemo<GraphContent>(() => {
    const byId = new Map(definition.stages.map((s, i) => [s.id, { stage: s, index: i }]))
    const cycles = cycleEdges(definition.stages)
    return {
      stage: (id) => {
        const found = byId.get(id)
        if (!found) return <GhostNode id={id} from={missing.get(id) ?? []} />
        return (
          <StageNode
            stage={found.stage}
            index={found.index}
            problems={byStage.get(id) ?? []}
            selected={selected === id}
            onSelect={onSelect && (() => onSelect({ stage: id }))}
          />
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
          selected === id && 'graph-selected',
        )
      },
      edgeClass: (from, to) =>
        cn(
          missing.has(from) && 'graph-edge-missing',
          cycles.has(`${from}->${to}`) && 'graph-edge-cycle',
          edge?.from === from && edge.to === to && 'graph-edge-selected',
        ) || undefined,
      connectable: (id) => byId.has(id),
    }
  }, [definition, missing, byStage, selected, edge, onSelect])
  const editing = useMemo<GraphEditing | undefined>(
    () =>
      onSelect && onConnect
        ? {
            onConnect,
            onEdgeSelect: (from, to) => onSelect({ from, to }),
            onPaneClick: () => onSelect(null),
          }
        : undefined,
    [onSelect, onConnect],
  )

  return (
    <WorkflowGraph
      stages={stages}
      content={content}
      label={`Grafo de ${definition.name ?? definition.key}`}
      interactive={editing != null}
      editing={editing}
    />
  )
}
