import { useQuery } from '@tanstack/react-query'
import { CirclePlay } from 'lucide-react'
import { useState } from 'react'
import { createPortal } from 'react-dom'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, WORK_ITEM_TYPES, type Run, type WorkItemSummary } from '../api'
import {
  ArchivedBanner,
  ArchiveMenu,
  ArchivedToggle,
  InheritedArchiveBanner,
} from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { LaunchForm } from '../components/launch/LaunchForm'
import { EmptyState } from '../components/list/DataTable'
import { ApprovalSheet } from '../components/approval/ApprovalSheet'
import { usePendingApprovals } from '../components/approval/usePendingApprovals'
import { Icon } from '../components/icons/Icon'
import { Button } from '../components/ui/Button'
import { Pill } from '../components/ui/Pill'
import { Sheet } from '../components/ui/Sheet'
import { LinesSkeleton } from '../components/ui/Skeleton'
import { formatAgo } from '../components/usage/quotaFormat'
import { attemptStatus, inOrder, runMillis } from '../components/work/attempts'
import { formatUsd, phaseInfo } from '../components/work/phase'
import { formatCompact, formatDateTime, formatDuration, statusLabel } from '../format'
import { useNow } from '../useNow'

const TYPE_LABEL = Object.fromEntries(WORK_ITEM_TYPES.map((t) => [t.value, t.label]))

/**
 * Trabajo (FS-E): lo que quieres conseguir, con qué workflow y cuántos intentos lleva. El intento
 * actual con sus fases, todos los intentos numerados, el resultado (la PR) y lo que ha costado.
 * «Nuevo intento» lanza un workflow en un panel lateral; si hay una aprobación pendiente, se revisa
 * en una hoja sin salir. En el móvil, la acción principal queda fija abajo. Archivado (él o su
 * proyecto), no se lanza nada.
 */
export function WorkItemPage() {
  const { workItemId = '' } = useParams()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const showArchived = params.get('archived') === 'true'
  const [launching, setLaunching] = useState(false)
  const [reviewing, setReviewing] = useState<string | null>(null)
  const item = useQuery({
    queryKey: ['work-item', workItemId],
    queryFn: () => api.workItem(workItemId),
  })
  const summary = useQuery({
    queryKey: ['work-item', workItemId, 'summary'],
    queryFn: () => api.workItemSummary(workItemId),
    enabled: item.data != null && item.data.archivedAt == null,
    refetchInterval: 15_000,
  })
  const projectId = item.data?.projectId
  const repos = useQuery({
    queryKey: ['repositories', projectId],
    queryFn: () => api.repositories(projectId!),
    enabled: !!projectId,
  })
  const project = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId!),
    enabled: !!projectId,
  })
  const runs = useQuery({
    queryKey: ['runs', workItemId, showArchived],
    queryFn: () => api.runs(workItemId, showArchived ? 'true' : undefined),
    refetchInterval: (q) => (q.state.data?.some((r) => r.finishedAt == null) ? 5_000 : false),
  })
  const approvals = usePendingApprovals()
  const archivedAt = item.data?.archivedAt ?? null
  const projectArchived = project.data?.archivedAt != null
  const canLaunch = !!item.data && archivedAt == null && !projectArchived
  const now = useNow(runs.data?.some((r) => r.finishedAt == null) ?? false)
  const attempts = inOrder(runs.data ?? [])
  const current = attempts.at(-1)
  const pending = (approvals.data ?? []).find((a) => a.origin.workItem.id === workItemId)
  const s = summary.data
  const phase = s ? phaseInfo(s.phase) : null
  const workflow = current?.workflow

  return (
    <>
      <ErrorMessage error={item.error} />
      <div className="page-head work-head">
        <div className="page-title">
          <h1>
            {item.data ? (
              <>
                <span className="key">{item.data.key}</span> {item.data.title}
              </>
            ) : (
              'Trabajo'
            )}
          </h1>
          {item.data && (
            <p className="work-meta">
              {phase && <Pill tone={phase.tone}>{phase.label}</Pill>}
              {s?.repositoryName && (
                <span className="tag">
                  <Icon name="repo" size={14} strokeWidth={2.25} />
                  {s.repositoryName}
                </span>
              )}
              {workflow && (
                <span className="tag">
                  <Icon name="workflows" size={14} strokeWidth={2.25} />
                  {workflow.key} v{workflow.version}
                </span>
              )}
              <span className="muted small">
                {TYPE_LABEL[item.data.type] ?? item.data.type} · creado el{' '}
                {formatDateTime(item.data.createdAt)}
                {item.data.externalRef && (
                  <>
                    {' '}
                    · <WorkItemOrigin externalRef={item.data.externalRef} />
                  </>
                )}
              </span>
            </p>
          )}
        </div>
        <div className="page-actions">
          {canLaunch && (
            <Button variant={pending ? 'secondary' : 'primary'} onClick={() => setLaunching(true)}>
              <Icon name="retry" size={18} strokeWidth={2.5} />
              Nuevo intento
            </Button>
          )}
          {pending && (
            <Button onClick={() => setReviewing(pending.id)}>
              <Icon name="approvals" size={18} strokeWidth={2.5} />
              Revisar la aprobación
            </Button>
          )}
          {item.data && (
            <ArchiveMenu
              target={{ kind: 'work-item', id: workItemId }}
              name={`${item.data.key} · ${item.data.title}`}
              archivedAt={archivedAt}
              onDeleted={() => void navigate(`/projects/${item.data.projectId}`)}
            />
          )}
        </div>
      </div>
      {archivedAt && (
        <ArchivedBanner
          target={{ kind: 'work-item', id: workItemId }}
          archivedAt={archivedAt}
          note="Sus ejecuciones no salen en las listas y no se pueden lanzar agentes."
        />
      )}
      {!archivedAt && projectArchived && project.data && (
        <InheritedArchiveBanner>
          El proyecto <Link to={`/projects/${project.data.id}`}>{project.data.key}</Link> está
          archivado: restáuralo para lanzar agentes.
        </InheritedArchiveBanner>
      )}

      {item.data && (
        <ul className="work-what">
          <li className="card">
            <Icon name="work-items" size={22} strokeWidth={2.5} />
            <div>
              <h2>Qué: el trabajo</h2>
              <p className="prewrap">{item.data.description ?? item.data.title}</p>
            </div>
          </li>
          <li className="card">
            <Icon name="workflows" size={22} strokeWidth={2.5} />
            <div>
              <h2>Cómo: el workflow</h2>
              {workflow ? (
                <p>
                  {workflow.name ?? workflow.key} v{workflow.version}.{' '}
                  <Link to={`/workflows/${encodeURIComponent(workflow.key)}`}>Ver workflow</Link>
                </p>
              ) : (
                <p className="muted">Se elige al lanzar el primer intento.</p>
              )}
            </div>
          </li>
          <li className="card">
            <Icon name="retry" size={22} strokeWidth={2.5} />
            <div>
              <h2>Intentos: las ejecuciones</h2>
              <p>{attemptsSummary(attempts, pending != null)}</p>
            </div>
          </li>
        </ul>
      )}

      <div className="work-layout">
        <div className="work-main">
          {current && !showArchived && (
            <CurrentAttempt run={current} n={attempts.length} now={now} />
          )}
          <section className="card" aria-labelledby="attempts-title">
            <div className="section-head">
              <h2 id="attempts-title">Todos los intentos</h2>
              <ArchivedToggle
                label="Archivados"
                on={showArchived}
                onChange={(on) => setParams(on ? { archived: 'true' } : {}, { replace: true })}
              />
            </div>
            <ErrorMessage error={runs.error} />
            {runs.isPending && <LinesSkeleton label="los intentos" lines={3} />}
            {runs.data &&
              (attempts.length === 0 ? (
                showArchived ? (
                  <EmptyState icon={CirclePlay} title="No hay intentos archivados." />
                ) : (
                  <EmptyState icon={CirclePlay} title="Todavía no hay ningún intento.">
                    <p>Cada intento es una ejecución del workflow sobre este trabajo.</p>
                    {canLaunch && (
                      <Button size="sm" onClick={() => setLaunching(true)}>
                        Lanzar el primer intento
                      </Button>
                    )}
                  </EmptyState>
                )
              ) : (
                <ol className="attempt-list" aria-label="Intentos del trabajo">
                  {[...attempts].reverse().map((r, i) => (
                    <AttemptRow
                      key={r.id}
                      run={r}
                      n={attempts.length - i}
                      current={i === 0 && !showArchived}
                      now={now}
                    />
                  ))}
                </ol>
              ))}
          </section>
        </div>
        <aside className="work-side" aria-label="Resultado y coste">
          <ResultCard summary={s} />
          {attempts.length > 0 && <CostCard runs={attempts} now={now} />}
        </aside>
      </div>

      {/* En el body: la página entra con una animación (transform) que encerraría el fixed. */}
      {(canLaunch || pending) &&
        createPortal(
          <div className="work-actionbar">
            {pending ? (
              <Button onClick={() => setReviewing(pending.id)}>Revisar la aprobación</Button>
            ) : (
              <Button onClick={() => setLaunching(true)}>Lanzar otro intento</Button>
            )}
            {pending && canLaunch && (
              <Button
                variant="secondary"
                aria-label="Lanzar otro intento"
                onClick={() => setLaunching(true)}
              >
                <Icon name="retry" size={20} strokeWidth={2.5} />
              </Button>
            )}
          </div>,
          document.body,
        )}

      <Sheet
        open={launching}
        onOpenChange={setLaunching}
        title="Nuevo intento"
        description="Cada fase con agente queda en cola hasta que un runner conectado la recoge."
      >
        {repos.data?.length === 0 ? (
          <p className="muted">
            Registra antes un repositorio en el{' '}
            <Link to={`/projects/${projectId}?tab=config`}>proyecto</Link>.
          </p>
        ) : (
          <LaunchForm
            workItemId={workItemId}
            repositories={repos.data ?? []}
            defaultRepositoryId={item.data?.repositoryId}
          />
        )}
      </Sheet>
      <ApprovalSheet
        approvalId={reviewing}
        workCostUsd={s?.costUsd ?? null}
        onClose={() => setReviewing(null)}
      />
    </>
  )
}

function attemptsSummary(attempts: Run[], waiting: boolean): string {
  if (attempts.length === 0) return 'Ninguno todavía.'
  const n = `${attempts.length} ${attempts.length === 1 ? 'intento' : 'intentos'}.`
  const last = attempts.at(-1)!
  if (waiting) return `${n} El actual espera tu aprobación.`
  if (last.finishedAt == null) return `${n} El actual está en marcha.`
  return `${n} El último: ${attemptStatus(last).label.toLowerCase()}.`
}

const STAGE_TONE: Record<string, string> = {
  SUCCEEDED: 'ok',
  FAILED: 'bad',
  WAITING_FOR_APPROVAL: 'warn',
  WAITING_FOR_INPUT: 'warn',
  RUNNING: 'active',
  STARTING: 'active',
}

/** El intento actual con sus fases en orden, cada una con su estado. */
function CurrentAttempt({ run, n, now }: { run: Run; n: number; now: number }) {
  const started = run.startedAt ?? run.createdAt
  return (
    <section className="card" aria-labelledby="current-title">
      <div className="section-head">
        <h2 id="current-title">Intento actual</h2>
        <span className="muted small">
          <Link to={`/runs/${run.id}`}>Intento {n}</Link> ·{' '}
          {run.finishedAt
            ? `terminó ${formatAgo(run.finishedAt, now)}`
            : `empezó ${formatAgo(started, now)}`}
        </span>
      </div>
      <ol className="stage-flow" aria-label="Fases del intento actual">
        {run.stages.map((st) => (
          <li key={st.id} className={`stage-step tone-${STAGE_TONE[st.status] ?? 'idle'}`}>
            <span>{st.name ?? st.stageKey}</span>
            <span className="visually-hidden">: {statusLabel(st.status)}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}

function AttemptRow({
  run,
  n,
  current,
  now,
}: {
  run: Run
  n: number
  current: boolean
  now: number
}) {
  const status = attemptStatus(run)
  const detail = [
    run.workflow && `${run.workflow.key} v${run.workflow.version}`,
    run.cancelRequestedAt && run.status === 'CANCELLED' ? 'cancelado a mano' : null,
    `lanzado el ${formatDateTime(run.createdAt)}`,
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <li className="attempt-row">
      <span className="attempt-n">
        <Link to={`/runs/${run.id}`} className="row-link">
          Intento {n}
        </Link>
        {current && <small className="muted">actual</small>}
      </span>
      <span className="attempt-body">
        <Pill tone={status.tone}>{status.label}</Pill>
        <small className="muted">{detail}</small>
      </span>
      <span className="attempt-figures">
        <span>{formatDuration(runMillis(run, now))}</span>
        <span>{formatUsd(run.totals.costUsd)}</span>
      </span>
    </li>
  )
}

/** El resultado del trabajo: su PR, o qué falta para tenerla. */
function ResultCard({ summary: s }: { summary: WorkItemSummary | undefined }) {
  const pr = s?.pullRequest
  return (
    <section className="card side-card" aria-labelledby="result-title">
      <h2 id="result-title">Resultado</h2>
      {pr ? (
        <ul className="side-list">
          <li>
            <span
              className={`side-icon ${pr.status === 'MERGED' ? 'tone-ok' : 'tone-review'}`}
              aria-hidden="true"
            >
              <Icon name="branch" size={20} strokeWidth={2.5} />
            </span>
            <span>
              {pr.url ? (
                <a href={pr.url} target="_blank" rel="noreferrer">
                  PR #{pr.number}
                </a>
              ) : (
                `PR #${pr.number}`
              )}
              <small>
                {s?.repositoryName} ·{' '}
                {pr.status === 'MERGED'
                  ? 'fusionada: trabajo entregado'
                  : 'abierta, espera revisión'}
              </small>
            </span>
          </li>
        </ul>
      ) : (
        <p className="muted">
          Aún no hay cambios entregados. Cuando un intento abra la PR, aparecerá aquí.
        </p>
      )}
    </section>
  )
}

/** Lo que ha costado el trabajo: todos sus intentos, lo gastado sin resultado, tokens y tiempo. */
function CostCard({ runs, now }: { runs: Run[]; now: number }) {
  const sum = (of: (r: Run) => number | null) => {
    const values = runs.map(of).filter((v): v is number => v != null)
    return values.length === 0 ? null : values.reduce((a, b) => a + b, 0)
  }
  const total = sum((r) => r.totals.costUsd)
  const wasted = sum((r) =>
    r.status === 'FAILED' || r.status === 'CANCELLED' ? r.totals.costUsd : null,
  )
  const input = sum((r) => r.totals.inputTokens)
  const output = sum((r) => r.totals.outputTokens)
  const time = sum((r) => runMillis(r, now))
  return (
    <section className="card side-card" aria-labelledby="cost-title">
      <div className="section-head">
        <h2 id="cost-title">Lo que ha costado</h2>
        <span className="muted small">
          {runs.length === 1 ? 'el intento' : `los ${runs.length} intentos`}
        </span>
      </div>
      <p className="work-cost-total">{formatUsd(total)}</p>
      {wasted != null && wasted > 0 && (
        <p className="muted small">{formatUsd(wasted)} en intentos sin resultado</p>
      )}
      <dl className="work-cost-facts">
        <div>
          <dt>Tokens de entrada / salida</dt>
          <dd>
            {formatCompact(input)} / {formatCompact(output)}
          </dd>
        </div>
        <div>
          <dt>Tiempo de los intentos</dt>
          <dd>{formatDuration(time)}</dd>
        </div>
      </dl>
    </section>
  )
}

/**
 * De dónde viene el trabajo (DS): una issue o PR de GitHub, un disparador programado, o la
 * referencia tal cual.
 */
function WorkItemOrigin({ externalRef }: { externalRef: string }) {
  const github = /^github:([\w.-]+\/[\w.-]+)#(\d+)$/.exec(externalRef)
  if (github) {
    return (
      <a
        href={`https://github.com/${github[1]}/issues/${github[2]}`}
        target="_blank"
        rel="noreferrer"
      >
        Desde {github[1]}#{github[2]}
      </a>
    )
  }
  if (externalRef.startsWith('skynet:schedule:')) return <>Programado</>
  return <>{externalRef}</>
}
