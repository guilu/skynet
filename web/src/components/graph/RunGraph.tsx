import { Bot, Workflow } from 'lucide-react'
import { useMemo } from 'react'
import type { Run, StageRun } from '../../api'
import { formatDuration, statusLabel } from '../../format'
import { cn } from '../../lib/cn'
import { spanDuration, type Span } from '../run/runSpans'
import { stageNote } from '../run/stageNotes'
import { stageActivity, stepState } from '../run/stageState'
import { TYPE_ICON } from './definitionNodes'
import type { GraphStage } from './layout'
import WorkflowGraph, { type GraphContent } from './WorkflowGraph'
import { stageFacts, toRunGraphStages } from './runNodes'

/**
 * El grafo de la ejecución: el de su workflow con el estado de cada fase encima (último intento),
 * en vivo. Cada caja es un botón que abre la fase, como un paso del camino; un hijo de una
 * `parallel` abre su agente.
 */
export default function RunGraph({
  run,
  stages,
  spans,
  now,
  openKey,
  selectedAgentId,
  onChoose,
  onSelectAgent,
}: {
  run: Run
  /** El último intento de cada fase, en el orden del YAML. */
  stages: StageRun[]
  spans: Span[]
  now: number
  openKey: string | undefined
  selectedAgentId: string | undefined
  onChoose: (stage: StageRun) => void
  onSelectAgent: (agentId: string) => void
}) {
  // La ejecución llega nueva con cada evento: el grafo solo se recoloca si cambian sus fases.
  const shape = JSON.stringify(toRunGraphStages(stages))
  const graphStages = useMemo(() => JSON.parse(shape) as GraphStage[], [shape])
  const content = useMemo<GraphContent>(() => {
    const byKey = new Map(stages.map((s, i) => [s.stageKey, { stage: s, index: i }]))
    return {
      stage: (key) => {
        const found = byKey.get(key)
        if (!found) return key
        const { stage, index } = found
        const Icon = TYPE_ICON[stage.type ?? ''] ?? Workflow
        const { icon: StateIcon } = stepState(stage.status)
        const name = stage.name ?? stage.stageKey
        const activity = stageActivity(
          stage,
          spans.find((s) => s.id === stage.id),
          now,
        )
        const facts = stageFacts(stage)
        const note = stageNote(stage, run)
        const status = statusLabel(stage.status)
        return (
          <button
            type="button"
            className="graph-box graph-button"
            aria-pressed={stage.stageKey === openKey}
            aria-label={`${index + 1}. ${name}: ${status}${activity !== status ? `, ${activity}` : ''}`}
            onClick={() => onChoose(stage)}
          >
            <span className="graph-title">
              <Icon size={16} aria-hidden="true" />
              <span className="graph-name" title={name}>
                <span className="graph-index">{index + 1}.</span> {name}
              </span>
            </span>
            <span className="graph-state">
              {StateIcon && <StateIcon size={14} strokeWidth={3} aria-hidden="true" />}
              <span className="graph-activity">
                {activity === status ? status : `${status} · ${activity}`}
              </span>
            </span>
            {(facts.length > 0 || note) && (
              <span className="graph-detail" title={[...facts, note].filter(Boolean).join(' · ')}>
                {[...facts, note].filter(Boolean).join(' · ')}
              </span>
            )}
          </button>
        )
      },
      child: (key, childId) => {
        const stage = byKey.get(key)?.stage
        const agent = stage?.agents.findLast((a) => a.childKey === childId)
        const span = agent && spans.find((s) => s.id === agent.id)
        const status = agent?.status ?? 'PENDING'
        const { icon: StateIcon } = stepState(status)
        const text =
          agent?.currentTool && !agent.finishedAt
            ? agent.currentTool
            : span && span.start != null
              ? `${statusLabel(status)} · ${formatDuration(spanDuration(span, now))}`
              : statusLabel(status)
        const body = (
          <>
            <span className="graph-title">
              <Bot size={14} aria-hidden="true" />
              <span className="graph-name" title={childId}>
                {childId}
              </span>
            </span>
            <span className="graph-state">
              {StateIcon && <StateIcon size={12} strokeWidth={3} aria-hidden="true" />}
              <span className="graph-activity">{text}</span>
            </span>
          </>
        )
        return agent ? (
          <button
            type="button"
            className="graph-box graph-button"
            aria-pressed={agent.id === selectedAgentId}
            aria-label={`${childId}: ${text}`}
            onClick={() => onSelectAgent(agent.id)}
          >
            {body}
          </button>
        ) : (
          <div className="graph-box">{body}</div>
        )
      },
      stageClass: (key) => {
        const stage = byKey.get(key)?.stage
        if (!stage) return undefined
        return cn(
          `graph-state-${stepState(stage.status).className}`,
          stage.stageKey === openKey && 'graph-open',
        )
      },
      childClass: (key, childId) => {
        const agent = byKey.get(key)?.stage.agents.findLast((a) => a.childKey === childId)
        return cn(
          `graph-state-${stepState(agent?.status ?? 'PENDING').className}`,
          agent && agent.id === selectedAgentId && 'graph-open',
        )
      },
      dimmedFrom: (key) => {
        const status = byKey.get(key)?.stage.status
        return status === 'SKIPPED' || status === 'CANCELLED'
      },
    }
  }, [stages, spans, now, run, openKey, selectedAgentId, onChoose, onSelectAgent])

  return (
    <WorkflowGraph
      stages={graphStages}
      content={content}
      label="Fases de la ejecución"
      interactive
    />
  )
}
