import { Bot, Workflow } from 'lucide-react'
import { useMemo } from 'react'
import type { StageDefinition, WorkflowModel } from '../../api'
import { stageDetail, stageHints, toGraphStages, TYPE_ICON } from './definitionNodes'
import WorkflowGraph, { type GraphContent } from './WorkflowGraph'

function StageNode({ stage, index }: { stage: StageDefinition; index: number }) {
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
    </div>
  )
}

/** El grafo de la definición: qué hace cada fase y de cuáles depende, sin estado. */
export default function DefinitionGraph({ definition }: { definition: WorkflowModel }) {
  const stages = useMemo(() => toGraphStages(definition), [definition])
  const content = useMemo<GraphContent>(() => {
    const byId = new Map(definition.stages.map((s, i) => [s.id, { stage: s, index: i }]))
    return {
      stage: (id) => {
        const found = byId.get(id)
        return found ? <StageNode stage={found.stage} index={found.index} /> : id
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
      stageClass: (id) => `graph-type-${byId.get(id)?.stage.type ?? 'unknown'}`,
    }
  }, [definition])

  return (
    <WorkflowGraph
      stages={stages}
      content={content}
      label={`Grafo de ${definition.name ?? definition.key}`}
    />
  )
}
