import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api'
import { ArchiveMenu } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { DeliveriesSection } from '../components/github/GitHubSettings'
import { useGitHub } from '../components/github/github'
import { LinesSkeleton } from '../components/ui/Skeleton'
import { formatDateTime } from '../format'

interface Archived {
  id: string
  name: string
  archivedAt: string
  to?: string
  target: Parameters<typeof ArchiveMenu>[0]['target']
}

/**
 * Administración › Mantenimiento (FS-F): lo archivado en un solo sitio, para restaurarlo o
 * eliminarlo, y las últimas entregas del webhook de GitHub. Antes estaba repartido en los filtros
 * «Archivados» de cada lista.
 */
export function MaintenancePage() {
  const projects = useQuery({
    queryKey: ['projects', 'archived'],
    queryFn: () => api.projects('true'),
  })
  const workflows = useQuery({
    queryKey: ['workflows', 'archived'],
    queryFn: () => api.workflows('true'),
  })
  const runners = useQuery({
    queryKey: ['runners', 'archived'],
    queryFn: () => api.runners('true'),
  })
  const github = useGitHub()
  const groups: { id: string; title: string; more?: string; rows: Archived[] | undefined }[] = [
    {
      id: 'projects',
      title: 'Proyectos archivados',
      more: '/projects?archived=true',
      rows: projects.data
        ?.filter((p) => p.archivedAt)
        .map((p) => ({
          id: p.id,
          name: `${p.key} · ${p.name}`,
          archivedAt: p.archivedAt!,
          to: `/projects/${p.id}`,
          target: { kind: 'project', id: p.id },
        })),
    },
    {
      id: 'workflows',
      title: 'Workflows archivados',
      more: '/workflows?archived=true',
      rows: workflows.data
        ?.filter((w) => w.archivedAt)
        .map((w) => ({
          id: w.key,
          name: w.name ?? w.key,
          archivedAt: w.archivedAt!,
          to: `/workflows/${w.key}`,
          target: { kind: 'workflow', id: w.key },
        })),
    },
    {
      id: 'runners',
      title: 'Runners olvidados',
      more: '/runners?archived=true',
      rows: runners.data
        ?.filter((r) => r.archivedAt)
        .map((r) => ({
          id: r.id,
          name: r.name,
          archivedAt: r.archivedAt!,
          target: { kind: 'runner', id: r.id },
        })),
    },
  ]
  return (
    <>
      <h1>Mantenimiento</h1>
      <p className="muted">
        Lo archivado se puede restaurar o eliminar del todo. Las ejecuciones archivadas están en{' '}
        <Link to="/runs?view=history&archived=true">el historial</Link>.
      </p>
      <ErrorMessage error={projects.error ?? workflows.error ?? runners.error} />
      <div className="maint-grid">
        {groups.map((g) => (
          <section key={g.id} className="card side-card" aria-labelledby={`maint-${g.id}`}>
            <div className="section-head">
              <h2 id={`maint-${g.id}`}>{g.title}</h2>
              {g.rows && <span className="muted small">{g.rows.length}</span>}
            </div>
            {!g.rows && <LinesSkeleton label={g.title.toLowerCase()} lines={2} />}
            {g.rows?.length === 0 && <p className="muted">Nada.</p>}
            {g.rows && g.rows.length > 0 && (
              <ul className="maint-list">
                {g.rows.map((r) => (
                  <li key={r.id}>
                    <span>
                      {r.to ? (
                        <Link to={r.to} className="row-link">
                          {r.name}
                        </Link>
                      ) : (
                        <strong>{r.name}</strong>
                      )}
                      <small className="muted">{formatDateTime(r.archivedAt)}</small>
                    </span>
                    <ArchiveMenu
                      size="sm"
                      target={r.target}
                      name={r.name}
                      archivedAt={r.archivedAt}
                    />
                  </li>
                ))}
              </ul>
            )}
          </section>
        ))}
      </div>
      {github.data?.configured && <DeliveriesSection />}
    </>
  )
}
