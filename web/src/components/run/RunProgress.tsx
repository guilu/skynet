import { lazy, Suspense, useState, type CSSProperties } from 'react'
import { Link } from 'react-router'
import type { AgentRun, Run, StageRun } from '../../api'
import { formatDuration, statusLabel } from '../../format'
import { cn } from '../../lib/cn'
import { StatusBadge } from '../StatusBadge'
import { useMediaQuery } from '../../useMediaQuery'
import { LinesSkeleton } from '../ui/Skeleton'
import { outputSummary } from './agentOutput'
import { spanDuration, type Span } from './runSpans'
import { RISK_LABEL } from '../approval/approvalFormat'
import { StageRetry } from './StageRetry'
import { ACTIVE, stageActivity, stepState, WAITING } from './stageState'
import {
  attemptOf,
  checkStates,
  earlierAttempts,
  FAILURE_LABEL,
  failureOf,
  isCorrecting,
  latestAttempts,
  stageNote,
  timeLimitNote,
  type CheckState,
} from './stageNotes'

const ORIGIN: Record<Exclude<AgentRun['kind'], 'START'>, string> = {
  RESUME: 'reanudación del',
  RETRY: 'reintento del',
  FORK: 'fork del',
}

// React Flow va en su propio trozo, como en el workflow.
const RunGraph = lazy(() => import('../graph/RunGraph'))

type ProgressView = 'graph' | 'list'
const VIEW_KEY = 'skynet.runProgress.view'

function readView(): ProgressView {
  try {
    return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'graph'
  } catch {
    return 'graph'
  }
}

/** Grafo o lista, recordado en este navegador. */
function useProgressView(): [ProgressView, (view: ProgressView) => void] {
  const [view, setView] = useState(readView)
  const choose = (next: ProgressView) => {
    setView(next)
    try {
      localStorage.setItem(VIEW_KEY, next)
    } catch {
      // La elección dura lo que la pestaña.
    }
  }
  return [view, choose]
}

/**
 * Progreso de la ejecución: sus fases como un grafo con sus dependencias (W7-B) o como un camino
 * horizontal, cada una con su estado. En el móvil o con una sola fase, siempre el camino. Al pulsar
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
  // Cada fase se ve con su último intento; los anteriores, en su detalle.
  const [peek, setPeek] = useState<{ stageKey: string; agentId: string | undefined } | null>(null)
  const stages = latestAttempts(run)
  const stageOfAgent = run.stages.find((s) => s.agents.some((a) => a.id === selectedAgentId))
  const openKey =
    (peek?.agentId === selectedAgentId ? peek?.stageKey : undefined) ?? stageOfAgent?.stageKey
  const open = stages.find((s) => s.stageKey === openKey)
  const openSpan = spans.find((s) => s.id === open?.id)
  const earlier = open ? earlierAttempts(open, run) : []
  const [view, setView] = useProgressView()
  const narrow = useMediaQuery('(max-width: 720px)')
  const canGraph = !narrow && stages.length > 1
  const graph = canGraph && view === 'graph'

  function choose(stage: StageRun) {
    if (stage.agents.length === 0) {
      setPeek({ stageKey: stage.stageKey, agentId: selectedAgentId })
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
        {canGraph && (
          <div className="segmented progress-view" role="group" aria-label="Ver las fases como">
            <button type="button" aria-pressed={graph} onClick={() => setView('graph')}>
              Grafo
            </button>
            <button type="button" aria-pressed={!graph} onClick={() => setView('list')}>
              Lista
            </button>
          </div>
        )}
      </div>
      <nav aria-label="Fases y agentes">
        {graph ? (
          <Suspense fallback={<LinesSkeleton label="el grafo" lines={4} />}>
            <RunGraph
              run={run}
              stages={stages}
              spans={spans}
              now={now}
              openKey={openKey}
              selectedAgentId={selectedAgentId}
              onChoose={choose}
              onSelectAgent={onSelectAgent}
            />
          </Suspense>
        ) : (
          <ol className="path" style={{ '--steps': stages.length } as CSSProperties}>
            {stages.map((stage, i) => {
              const { className, icon: Icon } = stepState(stage.status)
              const span = spans.find((s) => s.id === stage.id)
              const note = stageNote(stage, run)
              return (
                <li key={stage.id} className={cn('step', className)}>
                  <button
                    type="button"
                    className="step-button"
                    aria-pressed={stage.stageKey === openKey}
                    aria-label={`${i + 1}. ${stage.name ?? stage.stageKey}: ${statusLabel(stage.status)}`}
                    onClick={() => choose(stage)}
                  >
                    <span className="step-dot" aria-hidden="true">
                      {Icon ? <Icon size={26} strokeWidth={3} /> : i + 1}
                    </span>
                    <strong>{stage.name ?? stage.stageKey}</strong>
                    <small>{stageActivity(stage, span, now)}</small>
                  </button>
                  {note && <small className="step-note muted">{note}</small>}
                </li>
              )
            })}
          </ol>
        )}
        {open && (
          <div className="step-detail">
            <p className="step-detail-head">
              <strong>{open.name ?? open.stageKey}</strong> <StatusBadge status={open.status} />
              {open.attempt > 1 && (
                <span className="muted small">
                  {' '}
                  intento {open.attempt}
                  {attemptOf(open)}
                  {open.retriedBy && ` · lo pidió ${open.retriedBy}`}
                </span>
              )}
              {open.agent && <span className="muted small"> · agente {open.agent}</span>}
              {timeLimitNote(open) && <span className="muted small"> · {timeLimitNote(open)}</span>}
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
            <StageRetry key={open.id} stage={open} run={run} />
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
            {earlier.length > 0 && (
              <EarlierAttempts
                attempts={earlier}
                selectedAgentId={selectedAgentId}
                onSelectAgent={onSelectAgent}
              />
            )}
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
                      {agent.failure && agent.status === 'FAILED' && (
                        <p className="muted small agent-origin">
                          Falló: {FAILURE_LABEL[agent.failure]}
                        </p>
                      )}
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
      {stages.every((s) => s.agents.length === 0) && (
        <p className="muted small">Esta ejecución aún no tiene agentes.</p>
      )}
    </section>
  )
}

/**
 * Los intentos que fallaron antes del que se ve, con por qué fallaron y sus agentes, que siguen
 * abriéndose en la conversación y el inspector.
 */
function EarlierAttempts({
  attempts,
  selectedAgentId,
  onSelectAgent,
}: {
  attempts: StageRun[]
  selectedAgentId: string | undefined
  onSelectAgent: (agentId: string) => void
}) {
  return (
    <div className="stage-attempts small">
      <p className="muted">Intentos anteriores</p>
      <ul>
        {attempts.map((attempt) => (
          <li key={attempt.id}>
            Intento {attempt.attempt} <StatusBadge status={attempt.status} />
            {failureOf(attempt) && <span className="muted"> · {failureOf(attempt)}</span>}
            {attempt.agents.map((agent) => (
              <span key={agent.id}>
                {' · '}
                <button
                  type="button"
                  className="btn-link"
                  aria-current={agent.id === selectedAgentId ? 'true' : undefined}
                  onClick={() => onSelectAgent(agent.id)}
                >
                  ver {agent.childKey ?? 'el agente'}
                </button>
              </span>
            ))}
          </li>
        ))}
      </ul>
    </div>
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
  const stages = latestAttempts(run)
  const current = stages.filter((s) => [...ACTIVE, ...WAITING].includes(s.status))
  if (current.length > 0) {
    const names = current.map((s) => s.name ?? s.stageKey).join(', ')
    const attempt =
      current.length === 1 && current[0].attempt > 1 ? ` · intento ${current[0].attempt}` : ''
    return `${current.length === 1 ? 'Fase' : 'Fases'} ${names}${attempt}`
  }
  const done = stages.filter((s) => s.status === 'SUCCEEDED').length
  return `${done} de ${stages.length} ${stages.length === 1 ? 'fase completada' : 'fases completadas'}`
}
