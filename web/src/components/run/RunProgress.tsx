import { Check, Hand, Play, SkipForward, Square, X, type LucideIcon } from 'lucide-react'
import { useState, type CSSProperties } from 'react'
import { Link } from 'react-router'
import type { AgentRun, Run, StageRun } from '../../api'
import { formatDuration, statusLabel } from '../../format'
import { cn } from '../../lib/cn'
import { StatusBadge } from '../StatusBadge'
import { spanDuration, type Span } from './runSpans'
import { stageNote } from './stageNotes'

const ORIGIN: Record<Exclude<AgentRun['kind'], 'START'>, string> = {
  RESUME: 'reanudación del',
  RETRY: 'reintento del',
  FORK: 'fork del',
}

const ACTIVE = ['READY', 'STARTING', 'RUNNING']
const WAITING = ['WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL']

/** Aspecto de cada paso del camino: hecho, en curso, esperando a alguien, fallido, omitido… */
function stepState(status: string): { className: string; icon: LucideIcon | null } {
  if (status === 'SUCCEEDED') return { className: 'done', icon: Check }
  if (ACTIVE.includes(status)) return { className: 'now', icon: Play }
  if (WAITING.includes(status)) return { className: 'hold', icon: Hand }
  if (status === 'FAILED') return { className: 'fail', icon: X }
  if (status === 'SKIPPED') return { className: 'skip', icon: SkipForward }
  if (status === 'CANCELLED') return { className: 'skip', icon: Square }
  return { className: 'todo', icon: null }
}

/**
 * Progreso de la ejecución: sus fases como un camino horizontal, cada una con su estado. Al pulsar
 * una fase se ven debajo sus agentes; elegir un agente lo abre en la conversación y el inspector.
 */
export function RunProgress({
  run,
  spans,
  now,
  selectedAgentId,
  onSelectAgent,
}: {
  run: Run
  spans: Span[]
  now: number
  selectedAgentId: string | undefined
  onSelectAgent: (agentId: string) => void
}) {
  // Una fase sin agentes (omitida, pendiente) se abre sin cambiar el agente elegido; en cuanto se
  // elige otro agente, manda la fase de ese agente.
  const [peek, setPeek] = useState<{ stageId: string; agentId: string | undefined } | null>(null)
  const stageOfAgent = run.stages.find((s) => s.agents.some((a) => a.id === selectedAgentId))
  const openId = (peek?.agentId === selectedAgentId ? peek?.stageId : undefined) ?? stageOfAgent?.id
  const open = run.stages.find((s) => s.id === openId)
  const openSpan = spans.find((s) => s.id === openId)

  function choose(stage: StageRun) {
    if (stage.agents.length === 0) {
      setPeek({ stageId: stage.id, agentId: selectedAgentId })
      return
    }
    setPeek(null)
    if (!stage.agents.some((a) => a.id === selectedAgentId)) onSelectAgent(stage.agents.at(-1)!.id)
  }

  return (
    <section className="card run-progress" aria-labelledby="run-progress-title">
      <div className="card-head">
        <h2 id="run-progress-title">Progreso</h2>
        <span className="muted small">{progressSummary(run)}</span>
      </div>
      <nav aria-label="Fases y agentes">
        <ol className="path" style={{ '--steps': run.stages.length } as CSSProperties}>
          {run.stages.map((stage, i) => {
            const { className, icon: Icon } = stepState(stage.status)
            const span = spans.find((s) => s.id === stage.id)
            const running = stage.agents.find((a) => a.currentTool && !a.finishedAt)
            const note = stageNote(stage, run)
            return (
              <li key={stage.id} className={cn('step', className)}>
                <button
                  type="button"
                  className="step-button"
                  aria-pressed={stage.id === openId}
                  aria-label={`${i + 1}. ${stage.name ?? stage.stageKey}: ${statusLabel(stage.status)}`}
                  onClick={() => choose(stage)}
                >
                  <span className="step-dot" aria-hidden="true">
                    {Icon ? <Icon size={26} strokeWidth={3} /> : i + 1}
                  </span>
                  <strong>{stage.name ?? stage.stageKey}</strong>
                  <small>
                    {running?.currentTool ??
                      (span && span.start != null && stage.status !== 'SKIPPED'
                        ? formatDuration(spanDuration(span, now))
                        : statusLabel(stage.status))}
                  </small>
                </button>
                {note && <small className="step-note muted">{note}</small>}
              </li>
            )
          })}
        </ol>
        {open && (
          <div className="step-detail">
            <p className="step-detail-head">
              <strong>{open.name ?? open.stageKey}</strong> <StatusBadge status={open.status} />
              {openSpan?.detail && <span className="muted small"> {openSpan.detail}</span>}
              {open.agent && <span className="muted small"> · agente {open.agent}</span>}
            </p>
            {stageNote(open, run) && (
              <p className="step-detail-note muted small">{stageNote(open, run)}</p>
            )}
            {open.agents.length === 0 ? (
              <p className="muted small">Esta fase no tiene agentes.</p>
            ) : (
              <ul className="step-agents">
                {openSpan?.children.map((span) => {
                  const agent = open.agents.find((a) => a.id === span.id)!
                  return (
                    <li key={span.id}>
                      <button
                        type="button"
                        className="step-agent"
                        aria-label={`${span.label} ${span.detail}, ${statusLabel(span.status)}, ${formatDuration(spanDuration(span, now))}`}
                        aria-current={span.id === selectedAgentId ? 'true' : undefined}
                        onClick={() => onSelectAgent(span.id)}
                      >
                        <span>
                          {span.label} <span className="muted small">{span.detail}</span>
                        </span>
                        <StatusBadge status={span.status} />
                        <span className="step-time">{formatDuration(spanDuration(span, now))}</span>
                      </button>
                      {agent.kind !== 'START' && agent.parentAgentRunId && (
                        <p className="muted small agent-origin">
                          {ORIGIN[agent.kind]}{' '}
                          <Link to={`/agent-runs/${agent.parentAgentRunId}`}>
                            agente {agent.parentAgentRunId.slice(0, 8)}
                          </Link>
                        </p>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        )}
      </nav>
      {run.stages.every((s) => s.agents.length === 0) && (
        <p className="muted small">Esta ejecución aún no tiene agentes.</p>
      )}
    </section>
  )
}

/** «Fase review · intento 2» mientras corre; al terminar, cuántas fases acabaron de cada forma. */
function progressSummary(run: Run): string {
  const current = run.stages.filter((s) => [...ACTIVE, ...WAITING].includes(s.status))
  if (current.length > 0) {
    const names = current.map((s) => s.name ?? s.stageKey).join(', ')
    const attempt =
      current.length === 1 && current[0].attempt > 1 ? ` · intento ${current[0].attempt}` : ''
    return `${current.length === 1 ? 'Fase' : 'Fases'} ${names}${attempt}`
  }
  const done = run.stages.filter((s) => s.status === 'SUCCEEDED').length
  return `${done} de ${run.stages.length} ${run.stages.length === 1 ? 'fase completada' : 'fases completadas'}`
}
