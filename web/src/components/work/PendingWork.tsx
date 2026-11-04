import { useState } from 'react'
import { Link } from 'react-router'
import type { WorkItemSummary, WorkPhase } from '../../api'
import { cn } from '../../lib/cn'
import { Icon } from '../icons/Icon'
import { Pill } from '../ui/Pill'
import { attemptsLine, phaseInfo, PHASES } from './phase'

/**
 * Trabajos pendientes con chips de fase para filtrar (FS-C): cada uno con su fase, su repositorio,
 * su workflow, sus intentos y lo que ha costado. `limit` corta la lista (Inicio).
 */
export function PendingWork({ items, limit }: { items: WorkItemSummary[]; limit?: number }) {
  const [phase, setPhase] = useState<WorkPhase | null>(null)
  if (items.length === 0)
    return <p className="muted">Nada pendiente: todos los trabajos están entregados.</p>
  const phases = PHASES.filter((p) => items.some((w) => w.phase === p.id))
  const visible = items.filter((w) => phase == null || w.phase === phase)
  return (
    <>
      {phases.length > 1 && (
        <div className="chips phase-chips" role="group" aria-label="Fase">
          <button
            type="button"
            className={cn('chip', phase == null && 'chip-on')}
            aria-pressed={phase == null}
            onClick={() => setPhase(null)}
          >
            Todos
          </button>
          {phases.map((p) => (
            <button
              key={p.id}
              type="button"
              className={cn('chip', phase === p.id && 'chip-on')}
              aria-pressed={phase === p.id}
              onClick={() => setPhase(p.id)}
            >
              {p.label} ({items.filter((w) => w.phase === p.id).length})
            </button>
          ))}
        </div>
      )}
      <ul className="work-list">
        {visible.slice(0, limit).map((w) => (
          <li key={w.id}>
            <WorkRow item={w} />
          </li>
        ))}
      </ul>
    </>
  )
}

export function WorkRow({
  item: w,
  showProject,
}: {
  item: WorkItemSummary
  showProject?: boolean
}) {
  const info = phaseInfo(w.phase)
  return (
    <div className="work-row">
      <div className="work-row-main">
        <Link to={`/work-items/${w.id}`} className="row-link">
          <span className="key">{w.key}</span> {w.title}
        </Link>
        <span className="work-row-tags">
          {showProject && <span className="tag">{w.projectName}</span>}
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
      </div>
      <Pill tone={info.tone}>{info.label}</Pill>
      <span className="work-row-cost">{attemptsLine(w)}</span>
    </div>
  )
}
