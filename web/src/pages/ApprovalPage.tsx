import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, MessageSquare, RotateCcw, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import {
  api,
  type ApprovalAction,
  type ApprovalEvidence,
  type ApprovalEvidenceAgent,
  type ApprovalView,
} from '../api'
import {
  APPROVAL_STATUS_LABEL,
  APPROVAL_STATUS_TONE,
  DECISION_LABEL,
  RISK_LABEL,
  RISK_TONE,
} from '../components/approval/approvalFormat'
import { ErrorMessage } from '../components/ErrorMessage'
import { SkynySays } from '../components/companion/SkynySays'
import { JsonView } from '../components/run/JsonView'
import { StatusBadge } from '../components/StatusBadge'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import { Sheet } from '../components/ui/Sheet'
import { TableSkeleton } from '../components/ui/Skeleton'
import { formatCost, formatDateTime, providerName } from '../format'

/**
 * Una petición de aprobación (ADR-0001 §3.7): qué se pide, con qué riesgo y de dónde viene, la
 * evidencia que se guardó al pedirla, lo que se concede al aprobar, qué pasa con cada decisión y el
 * historial. Mientras está pendiente, se decide aquí.
 */
export function ApprovalPage() {
  const { approvalId } = useParams()
  const approval = useQuery({
    queryKey: ['approval', approvalId],
    queryFn: () => api.approval(approvalId!),
    refetchInterval: 10_000,
  })
  const a = approval.data
  // La puerta en la ejecución: si ya preguntó otra vez, su última petición es otra.
  const run = useQuery({
    queryKey: ['run', a?.workflowRunId],
    queryFn: () => api.run(a!.workflowRunId),
    enabled: !!a && a.status === 'CHANGES_REQUESTED',
    refetchInterval: 10_000,
  })
  const latest = run.data?.stages.find((s) => s.id === a?.stageRunId)?.approval
  // Lo último que decidiste aquí: Skyny reacciona (SY-C).
  const [decided, setDecided] = useState<{ id: string; action: ApprovalAction } | null>(null)

  if (approval.isPending) return <TableSkeleton label="la aprobación" columns={3} rows={4} />
  if (!a) return <ErrorMessage error={approval.error} />
  const { origin, consequences } = a.evidence

  return (
    <>
      <div className="approval-head">
        <p className="approval-kicker muted">
          Puerta <code>{origin.stage.name ?? a.stageKey}</code>
          {a.round > 1 && ` · ronda ${a.round}`} · pedida el {formatDateTime(a.createdAt)}
        </p>
        <h1>{a.action}</h1>
        <p className="approval-pills">
          <Pill tone={RISK_TONE[a.risk]}>{RISK_LABEL[a.risk]}</Pill>
          <Pill tone={APPROVAL_STATUS_TONE[a.status]}>{APPROVAL_STATUS_LABEL[a.status]}</Pill>
          {a.decidedAt && (
            <span className="muted small">Decidida el {formatDateTime(a.decidedAt)}</span>
          )}
        </p>
        <dl className="approval-origin">
          <div>
            <dt>Proyecto</dt>
            <dd>
              <Link to={`/projects/${origin.project.id}`}>
                {origin.project.key} · {origin.project.name}
              </Link>
            </dd>
          </div>
          <div>
            <dt>Trabajo</dt>
            <dd>
              <Link to={`/work-items/${origin.workItem.id}`}>
                {origin.workItem.key} · {origin.workItem.title}
              </Link>
            </dd>
          </div>
          <div>
            <dt>Ejecución</dt>
            <dd>
              <Link to={`/runs/${origin.run.id}`}>
                {origin.workflow.name ?? origin.workflow.key} v{origin.workflow.version}
              </Link>
            </dd>
          </div>
          <div>
            <dt>Repositorio</dt>
            <dd>{origin.repository.name}</dd>
          </div>
          <div>
            <dt>Coste hasta ahora</dt>
            <dd>{formatCost(a.evidence.costUsd)}</dd>
          </div>
        </dl>
      </div>
      <ErrorMessage error={approval.error} />

      {a.status === 'CHANGES_REQUESTED' && (
        <section className="card approval-correcting" aria-labelledby="correcting-title">
          <h2 id="correcting-title">
            {latest && latest.id !== a.id ? 'Corregido' : 'Corrigiendo'}
          </h2>
          <p>
            {a.evidence.revise?.stageKey ?? 'La fase'} recibió los cambios que se pidieron
            {a.correctionAgentRunId && (
              <>
                {' '}
                (<Link to={`/agent-runs/${a.correctionAgentRunId}`}>ver el agente que corrige</Link>
                )
              </>
            )}
            .{' '}
            {latest && latest.id !== a.id ? (
              <Link to={`/approvals/${latest.id}`}>
                Ver la ronda {latest.round} ({APPROVAL_STATUS_LABEL[latest.status].toLowerCase()})
              </Link>
            ) : (
              'Cuando termine, la puerta vuelve a preguntar con lo corregido.'
            )}
          </p>
        </section>
      )}

      {decided?.id === a.id && <SkynySays {...DECISION_REACTION[decided.action]} />}
      {a.status === 'PENDING' && (
        <DecisionPanel approval={a} onDecided={(action) => setDecided({ id: a.id, action })} />
      )}

      {a.evidence.changesRequested && (
        <section className="card" aria-labelledby="previous-title">
          <h2 id="previous-title">
            Cambios pedidos en la ronda {a.evidence.changesRequested.round}
          </h2>
          <blockquote className="approval-quote">{a.evidence.changesRequested.comment}</blockquote>
          <p className="muted small">
            {a.evidence.changesRequested.actor} ·{' '}
            {formatDateTime(a.evidence.changesRequested.createdAt)} ·{' '}
            <Link to={`/approvals/${a.evidence.changesRequested.approvalId}`}>
              ver la ronda {a.evidence.changesRequested.round}
            </Link>
          </p>
        </section>
      )}

      <section className="card" aria-labelledby="evidence-title">
        <h2 id="evidence-title">Lo que dejaron sus dependencias</h2>
        {a.evidence.dependencies.length === 0 && (
          <p className="muted">La puerta no depende de ninguna fase.</p>
        )}
        {a.evidence.dependencies.map((d) => (
          <div key={d.stageKey} className="approval-dependency">
            <h3>
              <code>{d.name ?? d.stageKey}</code> <span className="tag">{d.type}</span>{' '}
              <StatusBadge status={d.status} />
            </h3>
            {d.agents.length === 0 && <p className="muted small">Sin agentes.</p>}
            {d.agents.map((agent) => (
              <EvidenceAgent key={agent.agentRunId} agent={agent} />
            ))}
          </div>
        ))}
      </section>

      <section className="card" aria-labelledby="grants-title">
        <h2 id="grants-title">Lo que se permitirá al aprobar</h2>
        <Grants evidence={a.evidence} />
      </section>

      <section className="card" aria-labelledby="consequences-title">
        <h2 id="consequences-title">Qué pasa con cada decisión</h2>
        <dl className="approval-consequences">
          <dt>Aprobar</dt>
          <dd>{consequences.approve}</dd>
          <dt>Rechazar</dt>
          <dd>{consequences.reject}</dd>
          <dt>Pedir cambios</dt>
          <dd>
            {consequences.requestChanges ?? 'No se puede: la puerta no tiene una fase que corrija.'}
          </dd>
        </dl>
      </section>

      <section className="card" aria-labelledby="history-title">
        <h2 id="history-title">Historial</h2>
        {a.decisions.length === 0 ? (
          <p className="muted">Nadie ha decidido ni comentado todavía.</p>
        ) : (
          <ol className="approval-history">
            {a.decisions.map((d) => (
              <li key={d.id}>
                <strong>{d.actor}</strong> {(DECISION_LABEL[d.action] ?? d.action).toLowerCase()}{' '}
                <span className="muted small">{formatDateTime(d.createdAt)}</span>
                {d.comment && <blockquote className="approval-quote">{d.comment}</blockquote>}
              </li>
            ))}
          </ol>
        )}
      </section>
    </>
  )
}

/** Lo que dejó un agente: su salida, sus comprobaciones, sus cambios y sus artefactos. */
function EvidenceAgent({ agent }: { agent: ApprovalEvidenceAgent }) {
  return (
    <div className="approval-agent">
      <p>
        <Link to={`/agent-runs/${agent.agentRunId}`}>
          {agent.childKey ?? 'Agente'} {agent.agentRunId.slice(0, 8)}
        </Link>{' '}
        <StatusBadge status={agent.status} />
        <span className="muted small">
          {agent.provider && ` · ${providerName(agent.provider)}`}
          {agent.costUsd != null && ` · ${formatCost(agent.costUsd)}`}
        </span>
      </p>
      {agent.structuredOutput != null && (
        <JsonView value={agent.structuredOutput} label="Salida estructurada" />
      )}
      {agent.checks.length > 0 && (
        <ul className="stage-checks small" aria-label="Comprobaciones">
          {agent.checks.map((c, i) => (
            <li
              key={`${c.name}.${i}`}
              className={`stage-check ${c.passed === false ? 'failed' : c.passed ? 'passed' : 'waiting'}`}
            >
              <span className="stage-check-mark" aria-hidden="true">
                {c.passed === false ? '✗' : c.passed ? '✓' : '·'}
              </span>{' '}
              <strong>{c.name}</strong>
              {c.target && (
                <>
                  {' '}
                  <code>{c.target}</code>
                </>
              )}
              <span className="visually-hidden">
                : {c.passed === false ? 'no pasa' : c.passed ? 'pasa' : 'sin comprobar'}
              </span>
              {c.reason && <span className="muted"> · {c.reason}</span>}
            </li>
          ))}
        </ul>
      )}
      {agent.changes && (
        <p className="small">
          Rama <code>{agent.changes.branch}</code> · {agent.changes.commits}{' '}
          {agent.changes.commits === 1 ? 'commit' : 'commits'} · {agent.changes.files}{' '}
          {agent.changes.files === 1 ? 'fichero' : 'ficheros'}{' '}
          <span className="diff-add">+{agent.changes.insertions}</span>{' '}
          <span className="diff-del">−{agent.changes.deletions}</span>
          {agent.changes.uncommittedFiles > 0 &&
            ` · ${agent.changes.uncommittedFiles} sin confirmar`}
        </p>
      )}
      {agent.artifacts.length > 0 && (
        <p className="small muted">
          Artefactos:{' '}
          {agent.artifacts.map((artifact, i) => (
            <span key={artifact.id}>
              {i > 0 && ', '}
              <Link to={`/agent-runs/${agent.agentRunId}`}>{artifact.name}</Link>
            </span>
          ))}
        </p>
      )}
    </div>
  )
}

/** La política de las fases que desbloquea la aprobación. */
function Grants({ evidence }: { evidence: ApprovalEvidence }) {
  if (evidence.grants.length === 0)
    return <p className="muted">Aprobar no arranca ninguna fase con agente ni comando.</p>
  return (
    <ul className="definition-list">
      {evidence.grants.map((g) => (
        <li key={g.stage}>
          <code>{g.stage}</code> <span className="tag">{g.type}</span>
          <span className="muted small">
            {g.command ? (
              <>
                {' · Ejecuta '}
                <code>{g.command}</code>
              </>
            ) : (
              <>
                {' · Herramientas: '}
                {g.allowedTools.length > 0 ? g.allowedTools.join(', ') : 'las del repositorio'}
                {g.permissionMode && ` · ${g.permissionMode}`}
                {g.provider && ` · ${providerName(g.provider)}`}
              </>
            )}
            {g.environment && g.environment.length > 0 && ` · Entorno: ${g.environment.join(', ')}`}
          </span>
        </li>
      ))}
    </ul>
  )
}

const REQUIRES_COMMENT: Record<ApprovalAction, string | null> = {
  APPROVE: null,
  REJECT: 'Para rechazar, escribe el motivo.',
  REQUEST_CHANGES: 'Para pedir cambios, escribe qué cambiar.',
  COMMENT: 'Escribe el comentario.',
}

/**
 * Decidir: un comentario (obligatorio para rechazar, pedir cambios y comentar) y las cuatro
 * acciones. Aprobar algo de riesgo alto pide confirmarlo.
 */
/** Cómo reacciona Skyny a cada decisión: salta al aprobar y asiente serio al resto. */
const DECISION_REACTION: Record<ApprovalAction, { text: string; gesture: 'hop' | 'nod' }> = {
  APPROVE: { text: '¡Gracias! Sigo con ello.', gesture: 'hop' },
  REJECT: { text: 'Entendido: lo paro aquí.', gesture: 'nod' },
  REQUEST_CHANGES: { text: 'Vale: le paso tus cambios a la fase que corrige.', gesture: 'nod' },
  COMMENT: { text: 'Anotado.', gesture: 'nod' },
}

function DecisionPanel({
  approval,
  onDecided,
}: {
  approval: ApprovalView
  onDecided: (action: ApprovalAction) => void
}) {
  const queryClient = useQueryClient()
  const [comment, setComment] = useState('')
  const [missing, setMissing] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const decide = useMutation({
    mutationFn: (action: ApprovalAction) =>
      api.decideApproval(approval.id, action, comment.trim() || undefined),
    onSuccess: (view, action) => {
      onDecided(action)
      queryClient.setQueryData(['approval', approval.id], view)
      setComment('')
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: ['approvals'] })
      void queryClient.invalidateQueries({ queryKey: ['run', approval.workflowRunId] })
    },
  })
  const canRevise = approval.evidence.consequences.requestChanges != null

  function act(action: ApprovalAction) {
    const needs = REQUIRES_COMMENT[action]
    if (needs && !comment.trim()) {
      setMissing(needs)
      return
    }
    setMissing(null)
    if (action === 'APPROVE' && approval.risk === 'HIGH' && !confirming) {
      setConfirming(true)
      return
    }
    decide.mutate(action)
  }

  return (
    <section className="card approval-decide" aria-labelledby="decide-title">
      <h2 id="decide-title">Decidir</h2>
      <div className="form">
        <label>
          Comentario
          <textarea
            value={comment}
            onChange={(e) => {
              setComment(e.target.value)
              setMissing(null)
            }}
            rows={4}
            maxLength={10_000}
            aria-describedby="decide-hint"
            aria-invalid={missing ? true : undefined}
          />
          <span id="decide-hint" className="hint">
            Obligatorio para rechazar, pedir cambios o comentar.
          </span>
        </label>
        {missing && (
          <p className="error" role="alert">
            {missing}
          </p>
        )}
        <div className="form-actions approval-actions">
          <Button variant="success" onClick={() => act('APPROVE')} disabled={decide.isPending}>
            <Check size={18} strokeWidth={2.5} aria-hidden="true" />
            Aprobar
          </Button>
          <Button variant="danger" onClick={() => act('REJECT')} disabled={decide.isPending}>
            <X size={18} strokeWidth={2.5} aria-hidden="true" />
            Rechazar
          </Button>
          {canRevise && (
            <Button
              variant="secondary"
              onClick={() => act('REQUEST_CHANGES')}
              disabled={decide.isPending}
            >
              <RotateCcw size={18} strokeWidth={2.5} aria-hidden="true" />
              Pedir cambios
            </Button>
          )}
          <Button variant="secondary" onClick={() => act('COMMENT')} disabled={decide.isPending}>
            <MessageSquare size={18} strokeWidth={2.5} aria-hidden="true" />
            Comentar
          </Button>
        </div>
        <ErrorMessage error={decide.error} />
      </div>
      <Sheet
        open={confirming}
        onOpenChange={setConfirming}
        title="Aprobar con riesgo alto"
        description={approval.action}
      >
        <div className="form">
          <p>{approval.evidence.consequences.approve}</p>
          <div className="form-actions">
            <Button
              variant="success"
              onClick={() => decide.mutate('APPROVE')}
              disabled={decide.isPending}
            >
              <Check size={18} strokeWidth={2.5} aria-hidden="true" />
              Sí, aprobar
            </Button>
            <Button variant="secondary" onClick={() => setConfirming(false)}>
              Cancelar
            </Button>
          </div>
          <ErrorMessage error={decide.error} />
        </div>
      </Sheet>
    </section>
  )
}
