import { useQuery } from '@tanstack/react-query'
import { ChevronRight } from 'lucide-react'
import { Link, matchPath, useLocation } from 'react-router'
import { api } from '../../api'
import { NAV } from './nav'

interface Crumb {
  label: string
  to?: string
}

/**
 * Migas de la barra superior, sacadas de la ruta. Los nombres de proyecto, trabajo y ejecución
 * salen de las mismas consultas que usa la página, así que no cuestan peticiones de más.
 */
export function Breadcrumbs() {
  const { pathname } = useLocation()
  const projectId = matchPath('/projects/:projectId', pathname)?.params.projectId
  const workItemId = matchPath('/work-items/:workItemId', pathname)?.params.workItemId
  const runId = matchPath('/runs/:runId', pathname)?.params.runId

  const item = useQuery({
    queryKey: ['work-item', workItemId],
    queryFn: () => api.workItem(workItemId!),
    enabled: !!workItemId,
  })
  const projectOf = projectId ?? item.data?.projectId
  const project = useQuery({
    queryKey: ['project', projectOf],
    queryFn: () => api.project(projectOf!),
    enabled: !!projectOf,
  })
  const run = useQuery({
    queryKey: ['run', runId],
    queryFn: () => api.run(runId!),
    enabled: !!runId,
  })

  const projects: Crumb = { label: 'Proyectos', to: '/projects' }
  const projectCrumb = (to?: string): Crumb => ({
    label: project.data?.name ?? 'Proyecto',
    to,
  })
  let crumbs: Crumb[]
  if (projectId) crumbs = [projects, projectCrumb()]
  else if (workItemId)
    crumbs = [
      projects,
      projectCrumb(projectOf && `/projects/${projectOf}`),
      { label: item.data?.key ?? 'Trabajo' },
    ]
  else if (runId)
    crumbs = [
      { label: 'Ejecuciones', to: '/runs' },
      { label: run.data?.workItemKey ?? 'Ejecución' },
    ]
  else {
    const section = NAV.find((n) => (n.end ? pathname === n.to : pathname.startsWith(n.to)))
    crumbs = [{ label: section?.label ?? 'Página no encontrada' }]
  }

  return (
    <nav className="crumbs" aria-label="Migas">
      <ol>
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1
          return (
            <li key={i}>
              {c.to && !last ? (
                <Link to={c.to}>{c.label}</Link>
              ) : (
                <span aria-current={last ? 'page' : undefined}>{c.label}</span>
              )}
              {!last && (
                <ChevronRight className="crumbs-sep" size={16} strokeWidth={3} aria-hidden="true" />
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
