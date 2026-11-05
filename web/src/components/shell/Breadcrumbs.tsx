import { useQuery } from '@tanstack/react-query'
import { Link, matchPath, useLocation } from 'react-router'
import { api } from '../../api'
import { sectionOf } from './nav'
import { useAttempt } from '../work/attempts'
import { Icon } from '../icons/Icon'

interface Crumb {
  label: string
  to?: string
}

/**
 * Migas de la barra superior, sacadas de la ruta y encabezadas por el área (FS-A). Los nombres de
 * proyecto, trabajo y ejecución salen de las mismas consultas que usa la página, así que no
 * cuestan peticiones de más. Una ejecución cuelga de su trabajo como «Intento N» (FS-E). En el móvil solo se ve «‹ padre», o la sección si no hay padre.
 */
export function Breadcrumbs() {
  const { pathname } = useLocation()
  const projectId = matchPath('/projects/:projectId', pathname)?.params.projectId
  const workItemId = matchPath('/work-items/:workItemId', pathname)?.params.workItemId
  const runId = matchPath('/runs/:runId', pathname)?.params.runId
  const workflowKey = matchPath('/workflows/:key', pathname)?.params.key
  const approvalId = matchPath('/approvals/:approvalId', pathname)?.params.approvalId

  const item = useQuery({
    queryKey: ['work-item', workItemId],
    queryFn: () => api.workItem(workItemId!),
    enabled: !!workItemId,
  })
  const run = useQuery({
    queryKey: ['run', runId],
    queryFn: () => api.run(runId!),
    enabled: !!runId,
  })
  const projectOf = projectId ?? item.data?.projectId ?? run.data?.projectId ?? undefined
  const project = useQuery({
    queryKey: ['project', projectOf],
    queryFn: () => api.project(projectOf!),
    enabled: !!projectOf,
  })
  const attempt = useAttempt(run.data)

  const projects: Crumb = { label: 'Proyectos', to: '/projects' }
  const projectCrumb = (to?: string): Crumb => ({
    label: project.data?.name ?? 'Proyecto',
    to,
  })
  let crumbs: Crumb[]
  let area: string | undefined
  if (projectId || workItemId || runId) area = 'Espacio de trabajo'
  else if (approvalId || workflowKey) area = 'Fábrica'
  if (projectId) crumbs = [projects, projectCrumb()]
  else if (workItemId)
    crumbs = [
      projects,
      projectCrumb(projectOf && `/projects/${projectOf}`),
      { label: item.data?.key ?? 'Trabajo' },
    ]
  else if (runId)
    // FS-E: una ejecución es un intento de un trabajo: Proyecto › Trabajo › Intento N.
    crumbs = [
      projects,
      projectCrumb(projectOf && `/projects/${projectOf}`),
      {
        label: run.data?.workItemKey ?? 'Trabajo',
        to: run.data ? `/work-items/${run.data.workItemId}` : undefined,
      },
      { label: attempt ? `Intento ${attempt.number}` : 'Intento' },
    ]
  else if (approvalId)
    crumbs = [{ label: 'Aprobaciones', to: '/approvals' }, { label: 'Aprobación' }]
  else if (workflowKey)
    crumbs = [
      { label: 'Workflows', to: '/workflows' },
      { label: workflowKey === 'new' ? 'Nuevo' : workflowKey },
    ]
  else {
    const section = sectionOf(pathname)
    area = section?.area
    crumbs = [{ label: section?.label ?? 'Página no encontrada' }]
  }
  const parent = crumbs.slice(0, -1).findLast((c) => c.to)

  return (
    <nav className="crumbs" aria-label="Migas" data-back={parent ? true : undefined}>
      {parent?.to && (
        <Link to={parent.to} className="crumbs-back" aria-label={`Volver a ${parent.label}`}>
          <Icon name="chevron-left" size={20} strokeWidth={2.5} />
          {parent.label}
        </Link>
      )}
      <ol>
        {area && (
          <li className="crumbs-area">
            <span>{area}</span>
            <Icon name="chevron-right" className="crumbs-sep" size={16} strokeWidth={3} />
          </li>
        )}
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
                <Icon name="chevron-right" className="crumbs-sep" size={16} strokeWidth={3} />
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
