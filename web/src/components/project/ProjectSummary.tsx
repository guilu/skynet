import { ArrowRight } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import type {
  ApprovalSummary,
  ChangeView,
  ProjectOverview,
  RepositoryOverview,
  WorkItemSummary,
  WorkPhase,
} from '../../api'
import { RISK_LABEL } from '../approval/approvalFormat'
import { Icon } from '../icons/Icon'
import { useIconPack } from '../icons/pack'
import type { IconName } from '../icons/registry'
import { formatAgo } from '../usage/quotaFormat'
import { attemptsLine, byPhase, CHANGE_STATUS, formatUsd } from '../work/phase'
import { cn } from '../../lib/cn'

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/**
 * Las cinco cifras del proyecto (FS-D): trabajos abiertos, ejecuciones en curso, PRs abiertas y
 * fusionadas, aprobaciones pendientes y coste a 30 días. En el móvil, chips deslizables.
 */
export function ProjectKpis({ overview: o }: { overview: ProjectOverview }) {
  const art = useIconPack() !== 'lucide'
  const tiles: { label: string; value: string; icon: IconName; tone: string; to?: string }[] = [
    {
      label: o.openWorkItems === 1 ? 'trabajo abierto' : 'trabajos abiertos',
      value: String(o.openWorkItems),
      icon: 'work-items',
      tone: 'primary',
      to: '?tab=work',
    },
    {
      label: o.activeRuns === 1 ? 'ejecución en curso' : 'ejecuciones en curso',
      value: String(o.activeRuns),
      icon: 'runs',
      tone: 'live',
      to: '?tab=runs',
    },
    {
      label: `${o.openPullRequests === 1 ? 'PR abierta' : 'PRs abiertas'} · ${o.mergedPullRequests} ${o.mergedPullRequests === 1 ? 'fusionada' : 'fusionadas'}`,
      value: String(o.openPullRequests),
      icon: 'branch',
      tone: 'ok',
      to: '?tab=changes',
    },
    {
      label: o.pendingApprovals === 1 ? 'aprobación pendiente' : 'aprobaciones pendientes',
      value: String(o.pendingApprovals),
      icon: 'approvals',
      tone: 'warn',
    },
    {
      label: 'acumulado en 30 días',
      value: formatUsd(o.costUsd),
      icon: 'cost',
      tone: 'idle',
    },
  ]
  return (
    <dl className="kpis dash-kpis proj-kpis" aria-label="Cifras del proyecto">
      {tiles.map((t) => (
        <div key={t.icon} className={cn('kpi kpi-main', `kpi-${t.tone}`)}>
          <span className={cn('kpi-icon', art && 'kpi-icon-art')} aria-hidden="true">
            <Icon name={t.icon} size={art ? 44 : 24} strokeWidth={2.5} />
          </span>
          <dt>{t.label}</dt>
          <dd>{t.to ? <Link to={t.to}>{t.value}</Link> : t.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** Los repositorios del proyecto como tarjetas: rama, trabajos, en marcha, PRs y coste. */
export function RepositoryCards({ repositories }: { repositories: RepositoryOverview[] }) {
  if (repositories.length === 0)
    return (
      <p className="muted">
        Aún no hay repositorios. Regístralo en <Link to="?tab=config">Configuración</Link>.
      </p>
    )
  return (
    <ul className="repo-overview">
      {repositories.map((r) => (
        <li key={r.id} className="card repo-overview-card">
          <div className="repo-overview-head">
            <Icon name="repo" size={18} strokeWidth={2.25} />
            <strong>{r.name}</strong>
            <span className="tag">
              <Icon name="branch" size={14} strokeWidth={2.25} />
              {r.defaultBranch}
            </span>
          </div>
          <dl className="repo-overview-facts">
            <div>
              <dt>{r.openWorkItems === 1 ? 'trabajo' : 'trabajos'}</dt>
              <dd>{r.openWorkItems}</dd>
            </div>
            <div>
              <dt>en marcha</dt>
              <dd>{r.activeRuns}</dd>
            </div>
            <div>
              <dt>{r.openPullRequests === 1 ? 'PR abierta' : 'PRs abiertas'}</dt>
              <dd>{r.openPullRequests}</dd>
            </div>
            <div>
              <dt>en 30 días</dt>
              <dd>{formatUsd(r.costUsd)}</dd>
            </div>
          </dl>
        </li>
      ))}
    </ul>
  )
}

/** Columnas del tablero: cada una junta una o varias fases. */
const COLUMNS: { id: string; label: string; phases: WorkPhase[]; open: boolean }[] = [
  { id: 'todo', label: 'Por empezar', phases: ['TODO'], open: false },
  { id: 'running', label: 'En marcha', phases: ['RUNNING', 'REVIEW'], open: true },
  { id: 'needs', label: 'Te necesita', phases: ['WAITING', 'STUCK'], open: true },
  { id: 'done', label: 'Entregado', phases: ['DONE'], open: false },
]

const narrow = () =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(max-width: 720px)').matches

/**
 * Tablero de trabajos por fase (FS-D). En el escritorio, cuatro columnas; en el móvil, secciones
 * plegables con «Te necesita» y «En marcha» abiertas.
 */
export function PhaseBoard({ items }: { items: WorkItemSummary[] }) {
  const [mobile] = useState(narrow)
  return (
    <div className="phase-board">
      {COLUMNS.map((c) => {
        const cards = items.filter((w) => c.phases.includes(w.phase)).sort(byPhase)
        return (
          <details key={c.id} className="phase-col" open={!mobile || c.open}>
            <summary>
              <span>{c.label}</span>
              <span className="phase-col-count">{cards.length}</span>
            </summary>
            {cards.length === 0 ? (
              <p className="muted small phase-col-empty">Nada</p>
            ) : (
              <ul>
                {cards.map((w) => (
                  <li key={w.id}>
                    <WorkCard item={w} />
                  </li>
                ))}
              </ul>
            )}
          </details>
        )
      })}
    </div>
  )
}

function WorkCard({ item: w }: { item: WorkItemSummary }) {
  return (
    <div className="work-card">
      <Link to={`/work-items/${w.id}`} className="row-link">
        <span className="key">{w.key}</span> {w.title}
      </Link>
      <span className="work-row-tags">
        {w.repositoryName && (
          <span className="tag">
            <Icon name="repo" size={14} strokeWidth={2.25} />
            {w.repositoryName}
          </span>
        )}
        {w.workflowKey && (
          <span className="tag">
            <Icon name="workflows" size={14} strokeWidth={2.25} />
            {w.workflowKey}
          </span>
        )}
      </span>
      <small className="muted">
        {w.phase === 'STUCK' ? 'el último intento falló · ' : ''}
        {w.pullRequest?.number != null
          ? `PR #${w.pullRequest.number} ${w.pullRequest.status === 'MERGED' ? 'fusionada' : 'abierta'} · `
          : ''}
        {attemptsLine(w)}
      </small>
    </div>
  )
}

/** Lo que necesita intervención en este proyecto: sus aprobaciones y sus trabajos atascados. */
export function ProjectAttention({
  approvals,
  items,
  now,
}: {
  approvals: ApprovalSummary[]
  items: WorkItemSummary[]
  now: number
}) {
  const stuck = items.filter((w) => w.phase === 'STUCK')
  const total = approvals.length + stuck.length
  return (
    <section className="card side-card" aria-labelledby="project-attention-title">
      <h2 id="project-attention-title">Necesita intervención</h2>
      {total === 0 ? (
        <p className="muted">Nada: los agentes siguen solos.</p>
      ) : (
        <ul className="side-list">
          {approvals.map((a) => (
            <li key={a.id}>
              <span className="side-icon tone-warn" aria-hidden="true">
                <Icon name="approvals" size={20} strokeWidth={2.5} />
              </span>
              <span>
                <Link to={`/approvals/${a.id}`} className="row-link">
                  {a.action} de <span className="key">{a.origin.workItem.key}</span>
                </Link>
                <small>
                  {a.origin.repository.name} · {RISK_LABEL[a.risk]} · {formatAgo(a.createdAt, now)}
                </small>
              </span>
            </li>
          ))}
          {stuck.map((w) => (
            <li key={w.id}>
              <span className="side-icon tone-bad" aria-hidden="true">
                <Icon name="circle-error" size={20} strokeWidth={2.5} />
              </span>
              <span>
                <Link to={`/work-items/${w.id}`} className="row-link">
                  <span className="key">{w.key}</span> atascado: el último intento falló
                </Link>
                <small>
                  {[w.repositoryName, attemptsLine(w)].filter(Boolean).join(' · ')} sin resultado
                </small>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** Las últimas PRs del proyecto, con el enlace a la pestaña Cambios. */
export function RecentChanges({ changes, now }: { changes: ChangeView[]; now: number }) {
  return (
    <section className="card side-card" aria-labelledby="project-changes-title">
      <div className="section-head">
        <h2 id="project-changes-title">Cambios</h2>
        <Link to="?tab=changes" className="section-link">
          Historial
          <ArrowRight size={16} strokeWidth={2.25} aria-hidden="true" />
        </Link>
      </div>
      {changes.length === 0 ? (
        <p className="muted">Aún no hay PRs de este proyecto.</p>
      ) : (
        <ul className="side-list">
          {changes.slice(0, 3).map((c) => (
            <li key={c.id}>
              <span
                className={cn('side-icon', c.status === 'MERGED' ? 'tone-ok' : 'tone-review')}
                aria-hidden="true"
              >
                <Icon name="branch" size={20} strokeWidth={2.5} />
              </span>
              <span>
                <Link to={`/work-items/${c.workItemId}`} className="row-link">
                  PR{c.number != null ? ` #${c.number}` : ''} {c.title}
                </Link>
                <small>
                  {c.workItemKey} · {CHANGE_STATUS[c.status]} · {formatAgo(c.updatedAt, now)}
                </small>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** Coste por trabajo, de mayor a menor, con una barra relativa al más caro. */
export function CostByWork({ items }: { items: WorkItemSummary[] }) {
  const costly = items
    .filter((w) => (w.costUsd ?? 0) > 0)
    .sort((a, b) => (b.costUsd ?? 0) - (a.costUsd ?? 0))
    .slice(0, 5)
  const max = costly[0]?.costUsd ?? 0
  return (
    <section className="card side-card" aria-labelledby="project-cost-title">
      <h2 id="project-cost-title">Coste por trabajo</h2>
      {costly.length === 0 ? (
        <p className="muted">Aún no hay gasto.</p>
      ) : (
        <ul className="cost-bars">
          {costly.map((w) => (
            <li key={w.id}>
              <span className="cost-bars-head">
                <Link to={`/work-items/${w.id}`} className="row-link">
                  <span className="key">{w.key}</span>
                </Link>
                <small className="muted">
                  {[w.workflowKey, plural(w.attempts, 'intento', 'intentos')]
                    .filter(Boolean)
                    .join(' · ')}
                </small>
                <span className="cost-bars-value">{formatUsd(w.costUsd)}</span>
              </span>
              <span className="cost-bar" aria-hidden="true">
                <i style={{ width: `${Math.max(3, ((w.costUsd ?? 0) / max) * 100)}%` }} />
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
