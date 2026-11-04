import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  api,
  SESSION_KEY,
  type ApprovalSummary,
  type ChangeView,
  type DashboardSummary,
  type Run,
  type Runner,
  type WorkItemSummary,
} from '../api'
import { RISK_LABEL } from '../components/approval/approvalFormat'
import { usePendingApprovals } from '../components/approval/usePendingApprovals'
import { AttentionList, type Alert } from '../components/AttentionList'
import { CompanionHello } from '../components/companion/CompanionHello'
import { CompanionPeek } from '../components/companion/CompanionPeek'
import { useCompanion } from '../components/companion/context'
import { peekText } from '../components/companion/peek'
import { ErrorMessage } from '../components/ErrorMessage'
import { Icon } from '../components/icons/Icon'
import type { IconName } from '../components/icons/registry'
import { Button } from '../components/ui/Button'
import { Sheet } from '../components/ui/Sheet'
import { CardsSkeleton, LinesSkeleton } from '../components/ui/Skeleton'
import { formatAgo, quotaAlerts } from '../components/usage/quotaFormat'
import { NewWorkItemForm } from '../components/work/NewWorkItemForm'
import { PendingWork } from '../components/work/PendingWork'
import { ProjectCard } from '../components/work/ProjectCard'
import { attemptsLine, byPhase, formatUsd } from '../components/work/phase'
import { simulatedProviders } from '../format'
import { useNow } from '../useNow'

/** Zona del navegador: la semana del gasto sigue su calendario. */
const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

/** Trabajos pendientes y actividad que caben en Inicio; el resto, en Trabajos y Actividad. */
const PENDING_SHOWN = 5
const ACTIVITY_SHOWN = 5

/**
 * Inicio (FS-C): la vista operativa del espacio de trabajo. Saludo con lo que pasa (agentes
 * trabajando, lo que te necesita y el gasto de la semana) y «Nuevo trabajo»; lo que necesita tu
 * intervención, con su proyecto, repositorio e intento; una tarjeta por proyecto con sus
 * repositorios; los trabajos pendientes por fase y la actividad reciente contada como resultados.
 * Las métricas globales están en Consumo y los runners en Runners.
 */
export function HomePage() {
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
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
  const projects = useQuery({ queryKey: ['projects', undefined], queryFn: () => api.projects() })
  const pending = useQuery({
    queryKey: ['work-items', 'pending'],
    queryFn: () => api.pendingWorkItems(),
    refetchInterval: 15_000,
  })
  const week = useQuery({
    queryKey: ['dashboard', 'metrics', '7d'],
    queryFn: () => api.dashboardMetrics('7d', zone()),
    refetchInterval: 60_000,
  })
  const results = useQuery({ queryKey: ['results'], queryFn: api.results, refetchInterval: 30_000 })
  const recent = useQuery({
    queryKey: ['runs', 'list', 'recent'],
    queryFn: () => api.listRuns({ size: ACTIVITY_SHOWN }),
    refetchInterval: 10_000,
  })
  const approvals = usePendingApprovals()
  const now = useNow(true)
  const companion = useCompanion()

  const items = [...(pending.data ?? [])].sort(byPhase)
  const data = summary.data
  const alerts = data
    ? [
        ...attentionItems(data, approvals.data ?? [], items, now),
        ...simulatedAlerts(runners.data ?? []),
      ]
    : []
  const working = (runners.data ?? [])
    .filter((r) => r.status === 'ONLINE')
    .reduce((n, r) => n + r.activeAgents, 0)
  const busyProjects = new Set(data?.activeRuns.map((r) => r.projectId).filter(Boolean)).size
  const active = (projects.data ?? []).filter((p) => p.archivedAt == null)

  return (
    <>
      <div className="dash-head">
        <div>
          <h1>
            {greeting(new Date(now))}
            {session.data?.username ? `, ${session.data.username}` : ''}
          </h1>
          {data && runners.data && (
            <p className="dash-summary">
              {summaryLine(working, busyProjects, alerts.length, week.data?.costUsd ?? null)}
            </p>
          )}
        </div>
        <CompanionPeek
          state={companion.expression}
          text={peekText(companion.expression, companion.pendingApprovals)}
          gesture={companion.gesture}
          sweat={(data?.quotaWarnings.length ?? 0) > 0}
          className="dash-peek"
        />
        <div className="dash-actions">
          {projects.data && active.length === 0 ? (
            <Link to="/projects" className="btn">
              <Plus size={20} strokeWidth={2.75} aria-hidden="true" />
              Nuevo trabajo
            </Link>
          ) : (
            <Button onClick={() => setCreating(true)} disabled={!projects.data}>
              <Plus size={20} strokeWidth={2.75} aria-hidden="true" />
              Nuevo trabajo
            </Button>
          )}
        </div>
      </div>
      <ErrorMessage error={summary.error} />
      <CompanionHello approvals={approvals.data ?? []} />
      <AttentionList alerts={alerts} className="dash-section" title="Necesita tu intervención" />

      <section className="dash-section" aria-labelledby="projects-title">
        <div className="section-head">
          <h2 id="projects-title">Tus proyectos</h2>
          <Link to="/projects" className="section-link">
            Ver todos
            <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
          </Link>
        </div>
        <ErrorMessage error={projects.error} />
        {projects.isPending && <CardsSkeleton label="los proyectos" count={2} />}
        {projects.data && active.length === 0 && (
          <p className="muted">
            Aún no hay proyectos. <Link to="/projects">Crea el primero</Link> y registra su
            repositorio.
          </p>
        )}
        {active.length > 0 && (
          <ul className="home-projects">
            {active.map((p) => (
              <li key={p.id}>
                <ProjectCard project={p} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <div className="home-two">
        <section className="card home-card" aria-labelledby="pending-title">
          <div className="section-head">
            <h2 id="pending-title">Trabajos pendientes</h2>
            {items.length > 0 && (
              <Link to="/work" className="section-link">
                Ver los {items.length}
                <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
              </Link>
            )}
          </div>
          <ErrorMessage error={pending.error} />
          {pending.isPending && <LinesSkeleton label="los trabajos pendientes" lines={3} />}
          {pending.data && <PendingWork items={items} limit={PENDING_SHOWN} />}
        </section>
        <section className="card home-card" aria-labelledby="activity-title">
          <div className="section-head">
            <h2 id="activity-title">Actividad reciente</h2>
            <Link to="/activity" className="section-link">
              Toda la actividad
              <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
            </Link>
          </div>
          <ErrorMessage error={recent.error ?? results.error} />
          {recent.isPending && <LinesSkeleton label="la actividad reciente" lines={3} />}
          {recent.data && (
            <ActivityList
              entries={activity(recent.data.items, results.data ?? []).slice(0, ACTIVITY_SHOWN)}
              now={now}
            />
          )}
        </section>
      </div>

      <p className="home-note muted small">
        Las métricas globales (ejecuciones por día, tokens y duración mediana) están en{' '}
        <Link to="/usage">Administración › Consumo</Link>.
      </p>

      <Sheet open={creating} onOpenChange={setCreating} title="Nuevo trabajo">
        <NewWorkItemForm
          projects={active}
          onDone={(item) => {
            setCreating(false)
            void navigate(`/work-items/${item.id}`)
          }}
        />
      </Sheet>
    </>
  )
}

function greeting(date: Date) {
  const h = date.getHours()
  if (h >= 6 && h < 14) return 'Buenos días'
  if (h >= 14 && h < 21) return 'Buenas tardes'
  return 'Buenas noches'
}

/** «3 agentes trabajan en 2 proyectos · 2 cosas necesitan tu intervención · 6,84 US$ esta semana». */
function summaryLine(working: number, projects: number, alerts: number, weekCost: number | null) {
  const agents =
    working === 0
      ? 'Ningún agente trabajando'
      : `${working} ${working === 1 ? 'agente trabaja' : 'agentes trabajan'}` +
        (projects > 0 ? ` en ${projects} ${projects === 1 ? 'proyecto' : 'proyectos'}` : '')
  const attention =
    alerts === 0
      ? 'nada necesita tu intervención'
      : `${alerts} ${alerts === 1 ? 'cosa necesita' : 'cosas necesitan'} tu intervención`
  const cost = weekCost == null ? [] : [`${formatUsd(weekCost)} esta semana`]
  return [agents, attention, ...cost].join(' · ')
}

/** «Analizador de texto › demo-repo». */
const where = (project: string, repository: string | null) =>
  repository ? `${project} › ${repository}` : project

/**
 * Lo que necesita tu intervención: aprobaciones pendientes (con su proyecto, repositorio e
 * intento), trabajos atascados (el último intento falló) con lo gastado sin resultado, agentes
 * sin respuesta, runners sin latido, proveedores sin runner y cuotas casi agotadas.
 */
function attentionItems(
  data: DashboardSummary,
  approvals: ApprovalSummary[],
  items: WorkItemSummary[],
  now: number,
): Alert[] {
  const byId = new Map(items.map((w) => [w.id, w]))
  return [
    ...approvals.map((a) => {
      const w = byId.get(a.origin.workItem.id)
      const attempt = w && w.attempts > 0 ? [`intento ${w.attempts}`] : []
      return {
        key: `approval-${a.id}`,
        to: `/approvals/${a.id}`,
        icon: 'approvals' as const,
        tone: 'warn' as const,
        title: `${a.origin.workItem.key} · ${a.action}`,
        detail: [
          where(a.origin.project.name, a.origin.repository.name),
          ...attempt,
          RISK_LABEL[a.risk],
          formatAgo(a.createdAt, now),
        ].join(' · '),
        status: 'Esperando aprobación',
        action: 'Revisar',
      }
    }),
    ...items
      .filter((w) => w.phase === 'STUCK')
      .map((w) => ({
        key: `stuck-${w.id}`,
        to: `/work-items/${w.id}`,
        icon: 'circle-error' as const,
        tone: 'bad' as const,
        title: `${w.key} · ${w.title}: el último intento falló`,
        detail: [
          where(w.projectName, w.repositoryName),
          attemptsLine(w) + (w.costUsd != null ? ' gastados sin resultado' : ''),
        ].join(' · '),
        status: 'Atascado',
        action: 'Ver intentos',
      })),
    ...data.unresponsiveAgents.map((a) => ({
      key: `agent-${a.agentRunId}`,
      to: `/runs/${a.workflowRunId}?agent=${a.agentRunId}`,
      icon: 'status-paused' as const,
      tone: 'warn' as const,
      title: a.workItemKey ?? a.workflowRunId,
      detail: a.lastActivityAt
        ? `Sin actividad ${formatAgo(a.lastActivityAt, now)}`
        : 'Sin actividad',
      status: 'Sin respuesta',
      filled: true,
    })),
    ...data.staleRunners.map((r) => ({
      key: `runner-${r.id}`,
      to: '/runners',
      icon: 'status-offline' as const,
      tone: 'bad' as const,
      title: `Runner «${r.name}»`,
      detail: r.lastHeartbeatAt
        ? `Sin latido ${formatAgo(r.lastHeartbeatAt, now)}`
        : 'Aún no ha dado señales',
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

interface ActivityEntry {
  key: string
  to: string
  icon: IconName
  tone: 'ok' | 'bad' | 'active' | 'review'
  workKey: string
  text: string
  detail: string
  at: string
}

/**
 * La actividad reciente contada como resultados: PRs fusionadas («entregado») y abiertas, e
 * intentos que fallan, terminan o empiezan. Lo más reciente primero.
 */
function activity(runs: Run[], changes: ChangeView[]): ActivityEntry[] {
  const fromChanges = changes
    .filter((c) => c.status !== 'CLOSED')
    .map((c): ActivityEntry =>
      c.status === 'MERGED'
        ? {
            key: `pr-${c.id}`,
            to: `/work-items/${c.workItemId}`,
            icon: 'circle-ok',
            tone: 'ok',
            workKey: c.workItemKey,
            text: `entregado: PR${prNumber(c)} fusionada en ${c.repositoryName}`,
            detail: c.projectName,
            at: c.updatedAt,
          }
        : {
            key: `pr-${c.id}`,
            to: `/work-items/${c.workItemId}`,
            icon: 'branch',
            tone: 'review',
            workKey: c.workItemKey,
            text: `abrió la PR${prNumber(c)} en ${c.repositoryName}`,
            detail: `${c.projectName} · espera revisión`,
            at: c.createdAt,
          },
    )
  const fromRuns = runs
    .filter((r) => r.archivedAt == null)
    .map((r): ActivityEntry => {
      const key = r.workItemKey ?? r.id.slice(0, 8)
      const workflow = r.workflow?.name ?? r.workflow?.key
      const base = { key: `run-${r.id}`, to: `/runs/${r.id}`, workKey: key }
      if (r.status === 'FAILED')
        return {
          ...base,
          icon: 'circle-error',
          tone: 'bad',
          text: 'tiene un intento fallido',
          detail: workflow ?? '',
          at: r.finishedAt ?? r.createdAt,
        }
      if (r.status === 'SUCCEEDED')
        return {
          ...base,
          icon: 'status-ok',
          tone: 'ok',
          text: 'terminó un intento',
          detail: workflow ?? '',
          at: r.finishedAt ?? r.createdAt,
        }
      if (r.status === 'CANCELLED')
        return {
          ...base,
          icon: 'status-cancelled',
          tone: 'bad',
          text: 'canceló un intento',
          detail: workflow ?? '',
          at: r.finishedAt ?? r.createdAt,
        }
      return {
        ...base,
        icon: 'play',
        tone: 'active',
        text: workflow ? `empezó con «${workflow}»` : 'empezó un intento',
        detail: r.workItemTitle ?? '',
        at: r.startedAt ?? r.createdAt,
      }
    })
  return [...fromChanges, ...fromRuns].sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
}

const prNumber = (c: ChangeView) => (c.number != null ? ` #${c.number}` : '')

function ActivityList({ entries, now }: { entries: ActivityEntry[]; now: number }) {
  if (entries.length === 0) return <p className="muted">Aún no hay actividad.</p>
  return (
    <ul className="home-activity">
      {entries.map((e) => (
        <li key={e.key}>
          <span className={`home-activity-icon tone-${e.tone}`} aria-hidden="true">
            <Icon name={e.icon} size={20} strokeWidth={2.5} />
          </span>
          <span className="home-activity-text">
            <Link to={e.to} className="row-link">
              <span className="key">{e.workKey}</span> {e.text}
            </Link>
            {e.detail && <small>{e.detail}</small>}
          </span>
          <time dateTime={e.at} className="home-activity-at">
            {formatAgo(e.at, now)}
          </time>
        </li>
      ))}
    </ul>
  )
}
