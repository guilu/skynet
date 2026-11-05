import { useQuery } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { api, type ChangeView, type Run, type WorkItemSummary } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { Icon } from '../components/icons/Icon'
import { EmptyState } from '../components/list/DataTable'
import { RunsHistory } from '../components/run/RunsHistory'
import { Pill } from '../components/ui/Pill'
import { LinesSkeleton } from '../components/ui/Skeleton'
import { TabList, TabPanel } from '../components/ui/Tabs'
import { formatAgo } from '../components/usage/quotaFormat'
import { attemptStatus, failedStage, runMillis } from '../components/work/attempts'
import { formatUsd } from '../components/work/phase'
import { formatDuration } from '../format'
import { useNow } from '../useNow'

type View = 'live' | 'history'

/** Filtros del historial: si la URL trae alguno, se abre el historial. */
const HISTORY_PARAMS = ['status', 'since', 'projectId', 'q', 'archived', 'page']

const DAY = 24 * 60 * 60 * 1000

/**
 * Fábrica › En curso (FS-F): las ejecuciones activas y en cola agrupadas por proyecto ›
 * repositorio, cada una con su barra de fases, la fase actual, su duración y su coste; debajo,
 * los resultados de hoy y lo que terminó sin resultado. La lista completa con filtros es la
 * pestaña Historial (`?view=history`).
 */
export function FactoryPage() {
  const [params, setParams] = useSearchParams()
  const asked = params.get('view')
  const view: View =
    asked === 'history' || (asked == null && HISTORY_PARAMS.some((k) => params.has(k)))
      ? 'history'
      : 'live'
  return (
    <>
      <h1>En curso</h1>
      <TabList
        label="Vistas de la fábrica"
        tabs={[
          { id: 'live', label: 'En curso', icon: 'runs' },
          { id: 'history', label: 'Historial', icon: 'list' },
        ]}
        selected={view}
        onSelect={(next) => setParams(next === 'live' ? {} : { view: next }, { replace: true })}
        idPrefix="factory-tab"
        panelId="factory-panel"
      />
      <TabPanel id="factory-panel" labelledBy={`factory-tab-${view}`} className="tab-body">
        {view === 'history' ? <RunsHistory /> : <LiveRuns />}
      </TabPanel>
    </>
  )
}

function LiveRuns() {
  const now = useNow(true, 5_000)
  const active = useQuery({
    queryKey: ['runs', 'list', 'active'],
    queryFn: () => api.listRuns({ status: ['PENDING', 'RUNNING'], size: 100 }),
    refetchInterval: 5_000,
  })
  const items = useQuery({
    queryKey: ['work-items', 'pending'],
    queryFn: () => api.pendingWorkItems(),
    refetchInterval: 15_000,
  })
  const results = useQuery({ queryKey: ['results'], queryFn: api.results, refetchInterval: 30_000 })
  const since = new Date(Math.floor((now - DAY) / 60_000) * 60_000).toISOString()
  const failed = useQuery({
    queryKey: ['runs', 'list', 'unresolved', since],
    queryFn: () => api.listRuns({ status: ['FAILED', 'CANCELLED'], since, size: 10 }),
    refetchInterval: 30_000,
  })
  const byItem = new Map((items.data ?? []).map((w) => [w.id, w]))
  const runs = active.data?.items ?? []
  const groups = groupRuns(runs, byItem)
  const today = (results.data ?? []).filter((c) => now - Date.parse(c.updatedAt) < DAY)
  const waiting = runs.filter((r) => attemptStatus(r).label === 'Esperando aprobación').length
  const queued = runs.filter((r) => r.status === 'PENDING').length

  return (
    <>
      {active.data && (
        <p className="muted">{summary(runs.length - queued - waiting, queued, waiting)}</p>
      )}
      <ErrorMessage error={active.error} />
      {active.isPending && <LinesSkeleton label="las ejecuciones en curso" lines={4} />}
      {active.data && runs.length === 0 && (
        <EmptyState companion="calm" title="Nada en marcha ahora mismo.">
          <p>Lanza un intento desde un trabajo y aparecerá aquí con su progreso.</p>
        </EmptyState>
      )}
      {groups.map((g) => (
        <section key={g.key} className="card live-group" aria-label={g.label}>
          <header className="live-group-head">
            <Icon name="projects" size={18} strokeWidth={2.25} />
            <strong>{g.project}</strong>
            {g.repository && <span className="muted">› {g.repository}</span>}
            <span className="live-group-cost muted">{formatUsd(g.cost)} en curso</span>
          </header>
          <ul className="live-list">
            {g.runs.map(({ run, item }) => (
              <li key={run.id}>
                <LiveRow run={run} item={item} now={now} />
              </li>
            ))}
          </ul>
        </section>
      ))}

      <div className="home-two">
        <section className="card home-card" aria-labelledby="today-title">
          <div className="section-head">
            <h2 id="today-title">Resultados de hoy</h2>
            <Link to="/results" className="section-link">
              Todos los resultados
              <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
            </Link>
          </div>
          <ErrorMessage error={results.error} />
          {results.data && today.length === 0 && <p className="muted">Ninguna PR hoy todavía.</p>}
          {today.length > 0 && (
            <ul className="side-list">
              {today.map((c) => (
                <ChangeRow key={c.id} change={c} now={now} />
              ))}
            </ul>
          )}
        </section>
        <section className="card home-card" aria-labelledby="unresolved-title">
          <div className="section-head">
            <h2 id="unresolved-title">Terminadas sin resultado</h2>
            <span className="muted small">24 h</span>
          </div>
          <ErrorMessage error={failed.error} />
          {failed.data && failed.data.items.length === 0 && (
            <p className="muted">Ninguna en las últimas 24 horas.</p>
          )}
          {failed.data && failed.data.items.length > 0 && (
            <ul className="side-list">
              {failed.data.items.map((r) => (
                <li key={r.id}>
                  <span
                    className={`side-icon ${r.status === 'FAILED' ? 'tone-bad' : ''}`}
                    aria-hidden="true"
                  >
                    <Icon
                      name={r.status === 'FAILED' ? 'circle-error' : 'status-cancelled'}
                      size={20}
                      strokeWidth={2.5}
                    />
                  </span>
                  <span>
                    <Link to={`/runs/${r.id}`} className="row-link">
                      <span className="key">{r.workItemKey}</span>{' '}
                      {r.status === 'FAILED'
                        ? `falló${failedStage(r) ? ` en ${failedStage(r)}` : ''}`
                        : 'cancelada'}
                    </Link>
                    <small>
                      {formatUsd(r.totals.costUsd)} · {formatAgo(r.finishedAt ?? r.createdAt, now)}
                    </small>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  )
}

function summary(running: number, queued: number, waiting: number): string {
  const parts = [`${running} ${running === 1 ? 'ejecución en marcha' : 'ejecuciones en marcha'}`]
  if (queued > 0) parts.push(`${queued} en cola`)
  if (waiting > 0) parts.push(`${waiting} esperándote`)
  return parts.join(' · ')
}

interface Group {
  key: string
  label: string
  project: string
  repository: string | null
  cost: number | null
  runs: { run: Run; item: WorkItemSummary | undefined }[]
}

/** Agrupa las ejecuciones por proyecto › repositorio del trabajo, en orden de llegada. */
function groupRuns(runs: Run[], byItem: Map<string, WorkItemSummary>): Group[] {
  const groups = new Map<string, Group>()
  for (const run of runs) {
    const item = byItem.get(run.workItemId)
    const project = item?.projectName ?? 'Sin proyecto'
    const repository = item?.repositoryName ?? null
    const key = `${item?.projectId ?? '-'}/${item?.repositoryId ?? '-'}`
    const g = groups.get(key) ?? {
      key,
      label: repository ? `${project} › ${repository}` : project,
      project,
      repository,
      cost: null,
      runs: [],
    }
    g.runs.push({ run, item })
    if (run.totals.costUsd != null) g.cost = (g.cost ?? 0) + run.totals.costUsd
    groups.set(key, g)
  }
  return [...groups.values()]
}

const SEGMENT: Record<string, string> = {
  SUCCEEDED: 'ok',
  FAILED: 'bad',
  WAITING_FOR_APPROVAL: 'warn',
  WAITING_FOR_INPUT: 'warn',
  RUNNING: 'active',
  STARTING: 'active',
  SKIPPED: 'ok',
}

function LiveRow({ run, item, now }: { run: Run; item: WorkItemSummary | undefined; now: number }) {
  const current = run.stages.find((s) => s.id === run.currentStageRunId)
  const status = attemptStatus(run)
  const phase =
    run.status === 'PENDING' ? 'En cola' : (current?.name ?? current?.stageKey ?? status.label)
  return (
    <div className="live-row">
      <span className="live-row-work">
        <Link to={`/runs/${run.id}`} className="row-link">
          <span className="key">{run.workItemKey}</span> {run.workItemTitle}
        </Link>
        {item && item.attempts > 0 && <small className="muted">intento {item.attempts}</small>}
      </span>
      <span className="live-row-progress">
        <span
          className="stage-bar"
          role="img"
          aria-label={`Fases: ${run.stages.map((s) => s.name ?? s.stageKey).join(', ')}`}
        >
          {run.stages.map((s) => (
            <i key={s.id} className={`seg-${SEGMENT[s.status] ?? 'idle'}`} />
          ))}
        </span>
        <Pill tone={status.tone}>{phase}</Pill>
      </span>
      <span className="live-row-figures">
        {run.status !== 'PENDING' && (
          <>
            <span>{formatDuration(runMillis(run, now))}</span>
            <span>{formatUsd(run.totals.costUsd)}</span>
          </>
        )}
      </span>
    </div>
  )
}

function ChangeRow({ change: c, now }: { change: ChangeView; now: number }) {
  return (
    <li>
      <span
        className={`side-icon ${c.status === 'MERGED' ? 'tone-ok' : 'tone-review'}`}
        aria-hidden="true"
      >
        <Icon name="branch" size={20} strokeWidth={2.5} />
      </span>
      <span>
        <Link to={`/work-items/${c.workItemId}`} className="row-link">
          PR{c.number != null ? ` #${c.number}` : ''} en {c.repositoryName}{' '}
          {c.status === 'MERGED' ? 'fusionada' : c.status === 'OPEN' ? 'abierta' : 'cerrada'}
        </Link>
        <small>
          {c.workItemKey} · {formatAgo(c.updatedAt, now)}
        </small>
      </span>
    </li>
  )
}
