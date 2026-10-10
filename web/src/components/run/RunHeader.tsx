import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Square } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, type Run } from '../../api'
import { elapsed, formatCost, formatDuration, formatNumber, isTerminal } from '../../format'
import { useNow } from '../../useNow'
import type { StreamState } from '../../useEventStream'
import { STREAM } from './stream'
import { ArchivedBanner, ArchiveMenu } from '../archive/Archive'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { headlineAgent } from './headlineAgent'

/**
 * Cabecera operativa de una ejecución (ADR-0001 §3.5): estado, duración, runner, tokens, coste y
 * estado de la conexión en vivo. Todo sale de las proyecciones del backend.
 */
export function RunHeader({ run, stream }: { run: Run; stream: StreamState }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const agent = headlineAgent(run)
  const running = run.finishedAt == null
  const now = useNow(running)
  const runners = useQuery({
    // También los olvidados: la ejecución puede ser de uno que ya no está.
    queryKey: ['runners', 'all'],
    queryFn: () => api.runners('all'),
    enabled: !!agent?.runnerId,
  })
  const runner = runners.data?.find((r) => r.id === agent?.runnerId)
  // La verificación llega cuando el agente ya ha terminado.
  const verifications = useQuery({
    queryKey: ['verifications', agent?.id],
    queryFn: () => api.verifications(agent!.id),
    enabled: agent != null && isTerminal(agent.status),
  })
  const verification = verifications.data?.[0]
  // Con varias fases se cancela la ejecución entera; con una, su agente.
  const wholeRun = run.stages.length > 1
  const cancellable = wholeRun ? run.finishedAt == null : run.currentAgentRunId != null
  const [confirming, setConfirming] = useState(false)
  const cancel = useMutation({
    mutationFn: async () => {
      if (wholeRun) await api.cancelRun(run.id)
      else await api.cancelAgent(run.currentAgentRunId!)
    },
    onSuccess: () => {
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: ['run', run.id] })
    },
  })
  const { totals } = run
  const noTokens = [
    totals.inputTokens,
    totals.outputTokens,
    totals.cacheReadTokens,
    totals.cacheCreationTokens,
  ].every((n) => n == null)
  const connection = STREAM[stream]

  return (
    <header className="run-header card">
      <div className="run-header-title">
        <h1>
          {run.workItemKey && <Link to={`/work-items/${run.workItemId}`}>{run.workItemKey}</Link>}{' '}
          {run.workItemTitle ?? 'Ejecución'} <StatusBadge status={run.status} />
        </h1>
        <span className="connection" role="status">
          <Pill tone={connection.tone} icon={connection.icon}>
            <span className="visually-hidden">Conexión: </span>
            {connection.label}
          </Pill>
        </span>
        {/* Solo se archiva una ejecución terminada. */}
        {(!running || run.archivedAt) && (
          <ArchiveMenu
            target={{ kind: 'run', id: run.id }}
            name={`${run.workItemKey ?? 'Ejecución'} · ${run.workItemTitle ?? ''}`}
            archivedAt={run.archivedAt}
            onDeleted={() => void navigate(`/work-items/${run.workItemId}`)}
          />
        )}
      </div>
      {run.archivedAt && (
        <ArchivedBanner
          target={{ kind: 'run', id: run.id }}
          archivedAt={run.archivedAt}
          note="No sale en las listas ni en las métricas, y sus agentes no se pueden continuar."
        />
      )}
      <dl className="metrics">
        {run.workflow && (
          <div>
            <dt>Workflow</dt>
            <dd>
              <Link to={`/workflows/${encodeURIComponent(run.workflow.key)}`}>
                {run.workflow.name ?? run.workflow.key}
              </Link>{' '}
              <span className="muted small">
                {run.workflow.name && `${run.workflow.key} `}v{run.workflow.version}
              </span>
            </dd>
          </div>
        )}
        <div>
          <dt>Duración</dt>
          <dd>{formatDuration(elapsed(run.startedAt ?? run.createdAt, run.finishedAt, now))}</dd>
        </div>
        <div>
          <dt>Agente</dt>
          <dd>
            {agent ? <StatusBadge status={agent.status} /> : '—'}
            {agent?.currentTool && <span className="muted small"> · {agent.currentTool}</span>}
          </dd>
        </div>
        {verification && (
          <div>
            <dt>Verificación</dt>
            <dd>
              <StatusBadge status={verification.status} />
              {verification.tests && (
                <span className="muted small">
                  {' '}
                  ·{' '}
                  {verification.tests.total - verification.tests.failed - verification.tests.errors}
                  /{verification.tests.total} tests
                </span>
              )}
            </dd>
          </div>
        )}
        <div>
          <dt>Runner</dt>
          <dd>{runner?.name ?? (agent?.runnerId ? agent.runnerId.slice(0, 8) : 'sin asignar')}</dd>
        </div>
        <div>
          <dt>Tokens</dt>
          <dd>
            {noTokens ? (
              '—'
            ) : (
              <>
                {formatNumber(totals.inputTokens)} entrada · {formatNumber(totals.outputTokens)}{' '}
                salida
                <br />
                <span className="muted small">
                  caché: {formatNumber(totals.cacheReadTokens)} leídos ·{' '}
                  {formatNumber(totals.cacheCreationTokens)} escritos
                </span>
              </>
            )}
          </dd>
        </div>
        <div>
          <dt>Coste</dt>
          <dd>
            {formatCost(totals.costUsd)}
            {totals.costUsd == null && running && (
              <span className="muted small"> (llega al terminar)</span>
            )}
          </dd>
        </div>
      </dl>
      {cancellable && (
        <div className="run-actions">
          {confirming ? (
            <span className="confirm" role="group" aria-label="Confirmar la cancelación">
              <span className="small">
                {wholeRun
                  ? '¿Cancelar la ejecución? Se detienen sus agentes y no arranca ninguna fase más;' +
                    ' lo hecho se queda en los worktrees.'
                  : '¿Cancelar el agente? Se detiene su proceso; lo hecho hasta ahora se queda en' +
                    ' el worktree.'}
              </span>
              <Button variant="danger" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
                {cancel.isPending ? 'Cancelando…' : 'Sí, cancelar'}
              </Button>
              <Button variant="secondary" onClick={() => setConfirming(false)}>
                No
              </Button>
            </span>
          ) : (
            !cancel.isSuccess && (
              <Button variant="secondary-danger" onClick={() => setConfirming(true)}>
                <Square size={16} strokeWidth={2.75} aria-hidden="true" />
                {wholeRun ? 'Cancelar ejecución' : 'Cancelar agente'}
              </Button>
            )
          )}
          {cancel.isSuccess && (
            <span role="status" className="small">
              Cancelación solicitada:{' '}
              {wholeRun ? 'los agentes se detendrán' : 'el agente se detendrá'} en unos segundos.
            </span>
          )}
        </div>
      )}
      <ErrorMessage error={cancel.error} />
    </header>
  )
}
