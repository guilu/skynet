import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, type Project, type WorkItemSummary } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Icon } from '../icons/Icon'
import { Skeleton } from '../ui/Skeleton'
import { formatUsd, phaseInfo, PHASES } from './phase'

/**
 * Un proyecto en Inicio (FS-C): sus repositorios como filas (en marcha, PRs abiertas y coste a 30
 * días), una tira con sus trabajos abiertos por fase y un pie con lo que espera revisión.
 */
export function ProjectCard({ project }: { project: Project }) {
  const overview = useQuery({
    queryKey: ['project', project.id, 'overview'],
    queryFn: () => api.projectOverview(project.id),
    refetchInterval: 30_000,
  })
  const o = overview.data
  const titleId = `project-card-${project.id}`
  const open = (o?.workItems ?? []).filter((w) => w.phase !== 'DONE')
  return (
    <article className="card proj-card" aria-labelledby={titleId}>
      <header className="proj-card-head">
        <span className="proj-badge" aria-hidden="true">
          {project.key}
        </span>
        <div className="proj-card-title">
          <Link to={`/projects/${project.id}`} className="row-link" id={titleId}>
            {project.name}
          </Link>
          {o && (
            <small>
              {plural(o.repositories.length, 'repositorio', 'repositorios')} ·{' '}
              {plural(o.openWorkItems, 'trabajo abierto', 'trabajos abiertos')}
            </small>
          )}
        </div>
        {o && <PhaseStrip items={open} />}
      </header>
      <ErrorMessage error={overview.error} />
      {overview.isPending && <Skeleton className="proj-card-skeleton" />}
      {o && o.repositories.length === 0 && (
        <p className="muted small proj-card-empty">
          Sin repositorios. <Link to={`/projects/${project.id}?tab=config`}>Registra uno</Link> para
          lanzar trabajos.
        </p>
      )}
      {o && o.repositories.length > 0 && (
        <table className="proj-repos">
          <thead>
            <tr>
              <th scope="col">Repositorio</th>
              <th scope="col">En marcha</th>
              <th scope="col">PRs</th>
              <th scope="col" className="num">
                30 días
              </th>
            </tr>
          </thead>
          <tbody>
            {o.repositories.map((r) => (
              <tr key={r.id}>
                <th scope="row">
                  <span className="proj-repo-name">
                    <Icon name="repo" size={16} strokeWidth={2.25} />
                    {r.name} <small className="muted">{r.defaultBranch}</small>
                  </span>
                </th>
                <td>
                  {r.activeRuns > 0 ? (
                    <span className="tag tag-active">{r.activeRuns} en marcha</span>
                  ) : (
                    '—'
                  )}
                </td>
                <td>
                  {r.openPullRequests > 0 ? plural(r.openPullRequests, 'abierta', 'abiertas') : '—'}
                </td>
                <td className="num">{formatUsd(r.costUsd)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {o && (
        <footer className="proj-card-foot">
          {[
            plural(o.activeRuns, 'intento en marcha', 'intentos en marcha'),
            plural(o.openPullRequests, 'PR por revisar', 'PRs por revisar'),
            `${formatUsd(o.costUsd)} en 30 días`,
          ].join(' · ')}
        </footer>
      )}
    </article>
  )
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** Una raya por trabajo abierto, del color de su fase, con el recuento en texto. */
function PhaseStrip({ items }: { items: WorkItemSummary[] }) {
  if (items.length === 0) return null
  const counts = PHASES.map((p) => ({ ...p, n: items.filter((w) => w.phase === p.id).length }))
    .filter((p) => p.n > 0)
    .map((p) => `${p.n} ${p.label.toLowerCase()}`)
  const shown = [...items].sort(
    (a, b) => PHASES.findIndex((p) => p.id === a.phase) - PHASES.findIndex((p) => p.id === b.phase),
  )
  return (
    <span className="phase-strip" role="img" aria-label={`Trabajos abiertos: ${counts.join(', ')}`}>
      {shown.slice(0, 10).map((w) => (
        <i key={w.id} className={`phase-${phaseInfo(w.phase).tone}`} />
      ))}
    </span>
  )
}
