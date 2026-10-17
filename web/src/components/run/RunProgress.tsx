import { Check, Hand, Play, SkipForward, Square, X, type LucideIcon } from 'lucide-react'
import { useState, type CSSProperties } from 'react'
import { Link } from 'react-router'
import type { AgentRun, Run, StageRun } from '../../api'
import { formatDuration, statusLabel } from '../../format'
import { cn } from '../../lib/cn'
import { StatusBadge } from '../StatusBadge'
import { outputSummary } from './agentOutput'
import { spanDuration, type Span } from './runSpans'
import { RISK_LABEL } from '../approval/approvalFormat'
import { checkStates, isCorrecting, isVerifying, stageNote, type CheckState } from './stageNotes'

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
                    {(isVerifying(stage) ? 'Verificando' : null) ??
                      (isCorrecting(stage) ? 'Corrigiendo' : null) ??
                      (stage.status === 'WAITING_FOR_APPROVAL' ? 'Esperando aprobación' : null) ??
                      running?.currentTool ??
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
              {open.type === 'parallel' && (
                <span className="muted small"> · {open.agents.length} agentes a la vez</span>
              )}
              {open.command && (
                <span className="muted small">
                  {' · ejecuta '}
                  <code>{open.command}</code>
                </span>
              )}
            </p>
            {stageNote(open, run) && (
              <p className="step-detail-note muted small">{stageNote(open, run)}</p>
            )}
            {open.reads.length > 0 && (
              <p className="muted small">
                Recibe{' '}
                {open.reads.map((r, i) => (
                  <span key={r}>
                    {i > 0 && ', '}
                    <code>{r}</code>
                  </span>
                ))}
              </p>
            )}
            {open.checks.length > 0 && <StageChecks checks={checkStates(open)} />}
            {open.type === 'human-approval' ? (
              <GateDetail stage={open} />
            ) : open.agents.length === 0 ? (
              <p className="muted small">
                {open.type === 'conditional'
                  ? 'Esta fase solo evalúa su condición.'
                  : 'Esta fase no tiene agentes.'}
              </p>
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
                      {agent.provider === 'command' && agent.exitCode != null && (
                        <p className="muted small agent-origin">
                          Código de salida {agent.exitCode}
                        </p>
                      )}
                      {outputSummary(agent.structuredOutput) && (
                        <p className="muted small agent-origin">
                          Salida: {outputSummary(agent.structuredOutput)}
                        </p>
                      )}
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

/**
 * Una puerta de aprobación: qué pide, con qué riesgo y en qué ronda, y el enlace a la petición para
 * decidir o ver el historial. Mientras corrige, el agente que aplica los cambios.
 */
function GateDetail({ stage }: { stage: StageRun }) {
  const approval = stage.approval
  if (!approval) return <p className="muted small">Esta puerta aún no ha preguntado.</p>
  return (
    <div className="gate-detail">
      <p>
        <strong>{approval.action}</strong>{' '}
        <span className="muted small">
          · {RISK_LABEL[approval.risk]}
          {approval.round > 1 && ` · ronda ${approval.round}`}
        </span>
      </p>
      {isCorrecting(stage) && approval.correctionAgentRunId && (
        <p className="muted small">
          Se pidieron cambios:{' '}
          <Link to={`/agent-runs/${approval.correctionAgentRunId}`}>ver el agente que corrige</Link>
          . Al terminar, la puerta vuelve a preguntar.
        </p>
      )}
      <p>
        <Link
          to={`/approvals/${approval.id}`}
          className={approval.status === 'PENDING' ? 'btn btn-sm' : undefined}
        >
          {approval.status === 'PENDING' ? 'Revisar' : 'Ver la aprobación y su historial'}
        </Link>
      </p>
    </div>
  )
}

const CHECK_MARK: Record<CheckState['state'], string> = {
  waiting: '·',
  checking: '…',
  passed: '✓',
  failed: '✗',
}

const CHECK_LABEL: Record<CheckState['state'], string> = {
  waiting: 'sin comprobar',
  checking: 'comprobando',
  passed: 'pasa',
  failed: 'no pasa',
}

/** Lo que Skynet comprueba en el worktree de la fase, con el resultado de cada comprobación. */
function StageChecks({ checks }: { checks: CheckState[] }) {
  return (
    <ul className="stage-checks small" aria-label="Comprobaciones de la fase">
      {checks.map((c, i) => (
        <li key={`${c.name}.${i}`} className={cn('stage-check', c.state)}>
          <span className="stage-check-mark" aria-hidden="true">
            {CHECK_MARK[c.state]}
          </span>{' '}
          <strong>{c.name}</strong>
          {c.target && (
            <>
              {' '}
              <code>{c.target}</code>
            </>
          )}
          <span className="visually-hidden">: {CHECK_LABEL[c.state]}</span>
          {c.reasons.length > 0 && <span className="muted"> · {c.reasons.join('; ')}</span>}
        </li>
      ))}
    </ul>
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
