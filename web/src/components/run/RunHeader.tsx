import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Bot,
  Clock,
  Coins,
  Cpu,
  FlaskConical,
  Gauge,
  GitBranch,
  Server,
  Sparkles,
  Square,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, type AgentRun, type Run } from '../../api'
import {
  elapsed,
  formatCost,
  formatDuration,
  formatNumber,
  isTerminal,
  providerName,
  statusTone,
} from '../../format'
import { cn } from '../../lib/cn'
import { useNow } from '../../useNow'
import type { StreamState } from '../../useEventStream'
import { STREAM } from './stream'
import { ArchivedBanner, ArchiveMenu } from '../archive/Archive'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'
import { SimulatedPill } from '../ui/SimulatedPill'
import { statusIcon } from '../ui/statusIcons'
import { headlineShare, sharesByProvider, sharesLine } from '../usage/quotaFormat'
import { AgentActions } from './AgentActions'
import { headlineAgent } from './headlineAgent'

/**
 * Cabecera de una ejecución (ADR-0001 §3.5): estado, clave y título; workflow, runner, proveedor,
 * modelo y rama como chips; las acciones del agente elegido, y debajo duración, tokens, coste y
 * tests.
 * Todo sale de las proyecciones del backend.
 */
export function RunHeader({
  run,
  stream,
  agent,
}: {
  run: Run
  stream: StreamState
  /** Agente elegido en la página: a él se refieren las acciones. */
  agent: AgentRun | undefined
}) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const headline = headlineAgent(run)
  const running = run.finishedAt == null
  const now = useNow(running)
  // Primero lo del agente elegido; si no lo tiene (una fase `command` no tiene modelo), lo del
  // primer agente de la ejecución que sí lo tenga.
  const agents = [agent, ...run.stages.flatMap((s) => s.agents)]
  const pick = <T,>(of: (a: AgentRun) => T | null | undefined) =>
    agents.map((a) => a && of(a)).find((v) => v != null)
  const runnerId = pick((a) => a.runnerId)
  const model = pick((a) => a.model)
  const provider = pick((a) => (a.provider === 'command' ? null : a.provider))
  // Del agente elegido; sin él, si alguno de la ejecución usó un CLI simulado.
  const simulated = agent ? agent.simulated : agents.some((a) => a?.simulated)
  const branch = pick((a) => a.workspace?.branch)
  const runners = useQuery({
    // También los olvidados: la ejecución puede ser de uno que ya no está.
    queryKey: ['runners', 'all'],
    queryFn: () => api.runners('all'),
    enabled: !!runnerId,
  })
  const runner = runners.data?.find((r) => r.id === runnerId)
  // La verificación llega cuando el agente ya ha terminado.
  const verifications = useQuery({
    queryKey: ['verifications', headline?.id],
    queryFn: () => api.verifications(headline!.id),
    enabled: headline != null && isTerminal(headline.status),
  })
  const verification = verifications.data?.[0]
  // Con varias fases se cancela la ejecución entera; con una, su agente.
  const wholeRun = run.stages.length > 1
  // Se pidió cancelarla y sus agentes aún no han parado: «Cancelando», sin volver a ofrecerlo.
  const cancelling = run.cancelRequestedAt != null && run.finishedAt == null
  const cancellable =
    !cancelling && (wholeRun ? run.finishedAt == null : run.currentAgentRunId != null)
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
  const tone = statusTone(run.status)
  const tests = verification?.tests
  const quota = headlineShare(totals.quota)

  return (
    <header className="run-header">
      <div className="run-hero card">
        <HeroBadge tone={tone} icon={statusIcon(run.status, tone)} />
        <div className="hero-body">
          <div className="hero-top">
            {run.workItemKey && (
              <Link className="run-key" to={`/work-items/${run.workItemId}`}>
                {run.workItemKey}
              </Link>
            )}
            <StatusBadge status={cancelling ? 'CANCELLING' : run.status} />
            <span className="connection" role="status">
              <Pill tone={connection.tone} icon={connection.icon}>
                <span className="visually-hidden">Conexión: </span>
                {connection.label}
              </Pill>
            </span>
            {/* Solo se archiva una ejecución terminada. */}
            {(!running || run.archivedAt) && (
              <span className="hero-menu">
                <ArchiveMenu
                  target={{ kind: 'run', id: run.id }}
                  name={`${run.workItemKey ?? 'Ejecución'} · ${run.workItemTitle ?? ''}`}
                  archivedAt={run.archivedAt}
                  onDeleted={() => void navigate(`/work-items/${run.workItemId}`)}
                />
              </span>
            )}
          </div>
          <h1>{run.workItemTitle ?? 'Ejecución'}</h1>
          <ul className="hero-chips" aria-label="Detalles de la ejecución">
            {run.workflow && (
              <Chip icon={Workflow} label="Workflow">
                <Link to={`/workflows/${encodeURIComponent(run.workflow.key)}`}>
                  {run.workflow.name ?? run.workflow.key}
                </Link>{' '}
                v{run.workflow.version}
              </Chip>
            )}
            <Chip icon={Server} label="Runner">
              {runner?.name ?? (runnerId ? runnerId.slice(0, 8) : 'sin asignar')}
            </Chip>
            {provider && (
              <Chip icon={Bot} label="Proveedor">
                {providerName(provider)}
                {simulated && (
                  <>
                    {' '}
                    <SimulatedPill />
                  </>
                )}
              </Chip>
            )}
            {model && (
              <Chip icon={Cpu} label="Modelo">
                {model}
              </Chip>
            )}
            {branch && (
              <Chip icon={GitBranch} label="Rama">
                <span className="mono">{branch}</span>
              </Chip>
            )}
          </ul>
        </div>
        <div className="hero-actions">
          {agent && (
            <AgentActions agent={agent} retry={!run.workflow || run.workflow.key === 'adhoc'} />
          )}
          {cancellable &&
            (confirming ? (
              <span className="confirm" role="group" aria-label="Confirmar la cancelación">
                <span className="small">
                  {wholeRun
                    ? '¿Cancelar la ejecución? Se detienen sus agentes y no arranca ninguna fase' +
                      ' más; lo hecho se queda en los worktrees.'
                    : '¿Cancelar el agente? Se detiene su proceso; lo hecho hasta ahora se queda en' +
                      ' el worktree.'}
                </span>
                <Button
                  variant="danger"
                  onClick={() => cancel.mutate()}
                  disabled={cancel.isPending}
                >
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
            ))}
          {(cancel.isSuccess || cancelling) && (
            <span role="status" className="small">
              Cancelación solicitada:{' '}
              {wholeRun ? 'los agentes se detendrán' : 'el agente se detendrá'} en unos segundos.
            </span>
          )}
          <ErrorMessage error={cancel.error} />
        </div>
      </div>
      {run.archivedAt && (
        <ArchivedBanner
          target={{ kind: 'run', id: run.id }}
          archivedAt={run.archivedAt}
          note="No sale en las listas ni en las métricas, y sus agentes no se pueden continuar."
        />
      )}
      <dl className="kpis run-kpis">
        <Kpi icon={Clock} tone="live" label="Duración">
          {formatDuration(elapsed(run.startedAt ?? run.createdAt, run.finishedAt, now))}
        </Kpi>
        <Kpi
          icon={Sparkles}
          tone="primary"
          label="Tokens"
          sub={
            !noTokens && (
              <>
                <span className="kpi-line">
                  {formatNumber(totals.inputTokens)} entrada · {formatNumber(totals.outputTokens)}{' '}
                  salida
                </span>
                <span className="kpi-line">
                  caché: {formatNumber(totals.cacheReadTokens)} leídos ·{' '}
                  {formatNumber(totals.cacheCreationTokens)} escritos
                </span>
              </>
            )
          }
        >
          {noTokens ? '—' : formatNumber((totals.inputTokens ?? 0) + (totals.outputTokens ?? 0))}
        </Kpi>
        <Kpi
          icon={Coins}
          tone="warn"
          label="Coste"
          sub={totals.costUsd == null && running && 'Llega al terminar'}
        >
          {formatCost(totals.costUsd)}
        </Kpi>
        <Kpi
          icon={Gauge}
          tone="warn"
          label="Cuota"
          sub={
            quota
              ? sharesByProvider(totals.quota).map((p) => (
                  <span key={p.provider} className="kpi-line">
                    {sharesLine(p)}
                  </span>
                ))
              : running && 'Se atribuye al terminar cada agente'
          }
        >
          {quota ? (
            <>
              {quota.value} <small className="kpi-unit">{quota.suffix}</small>
            </>
          ) : (
            '—'
          )}
        </Kpi>
        {/* Sin verificación, los tests no aplican: el hueco se queda vacío. */}
        {verification ? (
          <Kpi
            icon={FlaskConical}
            tone={statusTone(verification.status) === 'bad' ? 'bad' : 'ok'}
            label="Tests"
            sub={<StatusBadge status={verification.status} />}
          >
            {tests ? `${tests.total - tests.failed - tests.errors}/${tests.total}` : '—'}
          </Kpi>
        ) : (
          <div className="kpi-empty" aria-hidden="true" />
        )}
      </dl>
    </header>
  )
}

/** Insignia grande con el icono del estado de la ejecución. */
function HeroBadge({ tone, icon: Icon }: { tone: string; icon: LucideIcon }) {
  return (
    <span className={cn('hero-badge', `hero-${tone}`)} aria-hidden="true">
      <Icon size={38} strokeWidth={2.75} />
    </span>
  )
}

function Chip({
  icon: Icon,
  label,
  children,
}: {
  icon: LucideIcon
  label: string
  children: ReactNode
}) {
  return (
    <li className="chip" title={label}>
      <Icon size={16} strokeWidth={2.5} aria-hidden="true" />
      <span className="visually-hidden">{label}: </span>
      {children}
    </li>
  )
}

function Kpi({
  icon: Icon,
  tone,
  label,
  sub,
  children,
}: {
  icon: LucideIcon
  tone: 'live' | 'primary' | 'warn' | 'ok' | 'bad'
  label: string
  sub?: ReactNode
  children: ReactNode
}) {
  return (
    <div className={cn('kpi kpi-main', `kpi-${tone}`)}>
      <span className="kpi-icon" aria-hidden="true">
        <Icon size={24} strokeWidth={2.5} />
      </span>
      <dt>{label}</dt>
      <dd>{children}</dd>
      {sub && <dd className="kpi-sub">{sub}</dd>}
    </div>
  )
}
