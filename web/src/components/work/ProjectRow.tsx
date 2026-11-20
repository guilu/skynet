import { useQuery } from '@tanstack/react-query'
import { CirclePlay, FolderGit2, GitPullRequest, ListChecks, ShieldCheck } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { api, type Project } from '../../api'
import { ArchiveMenu } from '../archive/Archive'
import { formatUsd } from './phase'

const day = new Intl.DateTimeFormat('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric' })

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/**
 * Un proyecto en la lista: tarjeta ancha con su clave, nombre y descripción y, en etiquetas
 * pequeñas, lo relevante de su centro de operaciones (repositorios, trabajos abiertos, ejecuciones
 * en marcha, PRs, aprobaciones pendientes y coste a 30 días).
 */
export function ProjectRow({ project: p }: { project: Project }) {
  const overview = useQuery({
    queryKey: ['project', p.id, 'overview'],
    queryFn: () => api.projectOverview(p.id),
    refetchInterval: 30_000,
  })
  const o = overview.data
  const created = p.createdAt ? new Date(p.createdAt) : null
  return (
    <article className="card proj-row" aria-label={`${p.key} · ${p.name}`}>
      <Link to={`/projects/${p.id}`} className="proj-row-title">
        <span className="proj-badge">{p.key}</span> <span className="proj-row-name">{p.name}</span>
      </Link>
      <div className="proj-row-body">
        {p.description && <p className="proj-row-desc">{p.description}</p>}
        <ul className="proj-row-bullets" aria-label="Resumen">
          {o ? (
            <>
              <Bullet icon={<FolderGit2 />}>
                {plural(o.repositories.length, 'repositorio', 'repositorios')}
              </Bullet>
              <Bullet icon={<ListChecks />}>
                {plural(o.openWorkItems, 'trabajo abierto', 'trabajos abiertos')}
              </Bullet>
              <Bullet icon={<CirclePlay />} tone={o.activeRuns > 0 ? 'active' : undefined}>
                {plural(o.activeRuns, 'ejecución en marcha', 'ejecuciones en marcha')}
              </Bullet>
              <Bullet icon={<GitPullRequest />}>
                {plural(o.openPullRequests, 'PR abierta', 'PRs abiertas')} · {o.mergedPullRequests}{' '}
                fusionadas
              </Bullet>
              <Bullet icon={<ShieldCheck />} tone={o.pendingApprovals > 0 ? 'warn' : undefined}>
                {plural(o.pendingApprovals, 'aprobación pendiente', 'aprobaciones pendientes')}
              </Bullet>
            </>
          ) : (
            !overview.error && <li className="proj-row-loading muted">Cargando el resumen…</li>
          )}
        </ul>
      </div>
      <div className="proj-row-side">
        {o && o.costUsd != null && (
          <span className="proj-row-cost">{formatUsd(o.costUsd)} en 30 días</span>
        )}
        {created && !Number.isNaN(created.getTime()) && (
          <span className="muted small">Creado el {day.format(created)}</span>
        )}
      </div>
      <div className="proj-row-menu">
        <ArchiveMenu
          size="sm"
          target={{ kind: 'project', id: p.id }}
          name={`${p.key} · ${p.name}`}
          archivedAt={p.archivedAt}
        />
      </div>
    </article>
  )
}

function Bullet({
  icon,
  tone,
  children,
}: {
  icon: ReactNode
  tone?: 'active' | 'warn'
  children: ReactNode
}) {
  return (
    <li className={tone ? `tag tag-${tone}` : 'tag'}>
      <span className="proj-row-icon" aria-hidden="true">
        {icon}
      </span>
      {children}
    </li>
  )
}
