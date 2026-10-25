import { useQuery } from '@tanstack/react-query'
import { CirclePlay, Server } from 'lucide-react'
import { Link } from 'react-router'
import { api, SESSION_KEY, type ApprovalSummary, type DashboardSummary, type Runner } from '../api'
import { RISK_LABEL, approvalWork } from '../components/approval/approvalFormat'
import { usePendingApprovals } from '../components/approval/usePendingApprovals'
import { AttentionList, type Alert } from '../components/AttentionList'
import { CompanionPeek } from '../components/companion/CompanionPeek'
import { useCompanion } from '../components/companion/context'
import { CompanionHello } from '../components/companion/CompanionHello'
import { peekText } from '../components/companion/peek'
import { DashboardMetrics, PeriodSelector } from '../components/DashboardMetrics'
import { ErrorMessage } from '../components/ErrorMessage'
import { RunsTable } from '../components/RunsTable'
import { Pill } from '../components/ui/Pill'
import { SimulatedPill } from '../components/ui/SimulatedPill'
import { quotaAlerts } from '../components/usage/quotaFormat'
import { formatDateTime, formatProviders, simulatedProviders } from '../format'
import { cn } from '../lib/cn'
import { useNow } from '../useNow'
import { TableSkeleton } from '../components/ui/Skeleton'

/**
 * Dashboard (ADR-0001 §3.2): saludo y resumen con el periodo y «Lanzar agente», con la mascota
 * asomando y su tarjeta de atajos (CO-C); después lo que requiere atención (solo si hay algo), las métricas del periodo, las ejecuciones activas y los
 * runners.
 */
export function DashboardPage() {
  const summary = useQuery({
    queryKey: ['dashboard'],
    queryFn: api.dashboard,
    refetchInterval: 10_000,
  })
  const runners = useQuery({
    queryKey: ['runners'],
    queryFn: () => api.runners(),
    refetchInterval: 15_000,
  })
  const session = useQuery({ queryKey: SESSION_KEY, queryFn: api.session })
  const approvals = usePendingApprovals()
  const now = useNow(true)
  const data = summary.data
  const alerts = data
    ? [...attentionItems(data, approvals.data ?? [], now), ...simulatedAlerts(runners.data ?? [])]
    : []
  const companion = useCompanion()
  const working = (runners.data ?? [])
    .filter((r) => r.status === 'ONLINE')
    .reduce((n, r) => n + r.activeAgents, 0)

  return (
    <>
      <div className="dash-head">
        <div>
          <h1>
            {greeting(new Date(now))}
            {session.data?.username ? `, ${session.data.username}` : ''}
          </h1>
          {data && runners.data && (
            <p className="dash-summary">{summaryLine(working, alerts.length)}</p>
          )}
        </div>
        <CompanionPeek
          state={companion.expression}
          text={peekText(companion.expression, companion.pendingApprovals)}
          className="dash-peek"
        />
        <div className="dash-actions">
          <PeriodSelector />
          <Link to="/projects" className="btn">
            <CirclePlay size={20} strokeWidth={2.5} aria-hidden="true" />
            Lanzar agente
          </Link>
        </div>
      </div>
      <ErrorMessage error={summary.error} />
      {summary.isPending && <TableSkeleton label="lo que requiere atención" columns={5} rows={3} />}
      <CompanionHello approvals={approvals.data ?? []} />
      <AttentionList alerts={alerts} className="dash-section" />
      <DashboardMetrics />
      {data && data.activeRunsTotal > 0 && (
        <section className="dash-section">
          <div className="section-head">
            <h2>Ejecuciones activas ({data.activeRunsTotal})</h2>
            <Link to="/runs?status=PENDING&status=RUNNING">Ver todas las activas</Link>
          </div>
          <RunsTable runs={data.activeRuns} now={now} label="Ejecuciones activas" />
        </section>
      )}
      <section className="dash-section" aria-labelledby="runners-title">
        <div className="section-head">
          <h2 id="runners-title">Runners</h2>
          <Link to="/runners">Ver runners</Link>
        </div>
        <ErrorMessage error={runners.error} />
        {runners.data && runners.data.length === 0 && (
          <p className="muted">Aún no hay runners registrados.</p>
        )}
        {runners.data && runners.data.length > 0 && (
          <ul className="kpis dash-kpis runner-cards">
            {runners.data.map((r) => (
              <li key={r.id}>
                <RunnerCard runner={r} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

function greeting(date: Date) {
  const h = date.getHours()
  if (h >= 6 && h < 14) return 'Buenos días'
  if (h >= 14 && h < 21) return 'Buenas tardes'
  return 'Buenas noches'
}

function summaryLine(working: number, alerts: number) {
  const agents = `${working} ${working === 1 ? 'agente trabajando' : 'agentes trabajando'}`
  if (alerts === 0) return `${agents}. Todo en orden.`
  return `${agents} y ${alerts} ${alerts === 1 ? 'cosa que necesita' : 'cosas que necesitan'} tu atención.`
}

/**
 * Lo que requiere atención: aprobaciones pendientes (primero las de más riesgo), fallidas en 24 h,
 * agentes sin respuesta, runners sin latido, agentes de un proveedor que no tiene ningún runner en
 * línea y cuotas de los CLIs casi agotadas o por encima de su ritmo.
 */
function attentionItems(
  data: DashboardSummary,
  approvals: ApprovalSummary[],
  now: number,
): Alert[] {
  return [
    ...approvals.map((a) => ({
      key: `approval-${a.id}`,
      to: `/approvals/${a.id}`,
      icon: 'approvals' as const,
      tone: 'warn' as const,
      title: a.action,
      detail: `${approvalWork(a)} · ${RISK_LABEL[a.risk]} · pedida el ${formatDateTime(a.createdAt)}`,
      status: 'Esperando aprobación',
    })),
    ...data.recentFailures.map((r) => ({
      key: `run-${r.id}`,
      to: `/runs/${r.id}`,
      icon: 'circle-error' as const,
      tone: 'bad' as const,
      title: workTitle(r.workItemKey, r.workItemTitle, r.id),
      detail: `Fallida el ${formatDateTime(r.finishedAt ?? r.createdAt)}`,
      status: 'Fallida',
    })),
    ...data.unresponsiveAgents.map((a) => ({
      key: `agent-${a.agentRunId}`,
      to: `/runs/${a.workflowRunId}?agent=${a.agentRunId}`,
      icon: 'status-paused' as const,
      tone: 'warn' as const,
      title: a.workItemKey ?? a.workflowRunId,
      detail: `Sin actividad desde el ${formatDateTime(a.lastActivityAt)}`,
      status: 'Sin respuesta',
      filled: true,
    })),
    ...data.staleRunners.map((r) => ({
      key: `runner-${r.id}`,
      to: '/runners',
      icon: 'status-offline' as const,
      tone: 'bad' as const,
      title: `Runner «${r.name}»`,
      detail: `Sin latido desde el ${formatDateTime(r.lastHeartbeatAt)}`,
      status: 'Desconectado',
    })),
    ...data.providerShortages.map((p) => ({
      key: `provider-${p.provider}`,
      to: '/runners',
      icon: 'status-pending' as const,
      tone: 'warn' as const,
      title: p.displayName,
      detail:
        p.queuedAgents === 1
          ? `1 agente espera: ningún runner en línea tiene ${p.displayName}`
          : `${p.queuedAgents} agentes esperan: ningún runner en línea tiene ${p.displayName}`,
      status: 'Sin runner',
    })),
    ...quotaAlerts(data.quotaWarnings, now),
  ]
}

/**
 * Runners en línea con algún CLI simulado (AR-A): sus agentes reproducen sesiones grabadas en vez
 * de ejecutar el agente real.
 */
function simulatedAlerts(runners: Runner[]): Alert[] {
  return runners
    .filter((r) => r.status === 'ONLINE' && r.archivedAt == null)
    .flatMap((r) => {
      const names = simulatedProviders(r.providers)
      if (names.length === 0) return []
      return [
        {
          key: `simulated-${r.id}`,
          to: '/runners',
          icon: 'simulated' as const,
          tone: 'warn' as const,
          title: `Runner «${r.name}»`,
          detail: `Usa ${names.join(' y ')} simulado${names.length > 1 ? 's' : ''}: sus agentes reproducen sesiones grabadas`,
          status: 'CLI simulado',
        },
      ]
    })
}

function workTitle(key: string | null, title: string | null, fallback: string) {
  if (key && title) return `${key} · ${title}`
  return key ?? title ?? fallback
}

/** Un runner como las tarjetas de métricas: nombre, estado y versión o último latido. */
function RunnerCard({ runner }: { runner: Runner }) {
  const online = runner.status === 'ONLINE'
  const agents = `${runner.activeAgents} ${runner.activeAgents === 1 ? 'agente activo' : 'agentes activos'}`
  const version = runner.providers.length > 0 ? formatProviders(runner.providers) : null
  return (
    <Link to="/runners" className={cn('kpi kpi-main', online ? 'kpi-ok' : 'kpi-bad')}>
      <span className="kpi-icon" aria-hidden="true">
        <Server size={24} strokeWidth={2.5} />
      </span>
      <span className="kpi-label">{runner.name}</span>
      <span className="kpi-value">
        <Pill tone={online ? 'ok' : 'bad'} icon={online ? undefined : 'status-offline'}>
          {online ? 'En línea' : 'Desconectado'}
        </Pill>
      </span>
      <span className="kpi-sub">
        {online && runner.providers.some((p) => p.simulated) && (
          <>
            <SimulatedPill />{' '}
          </>
        )}
        {online
          ? [version, agents].filter(Boolean).join(' · ')
          : `Último latido ${formatDateTime(runner.lastHeartbeatAt)}`}
      </span>
    </Link>
  )
}
