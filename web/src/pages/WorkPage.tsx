import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { api, type WorkPhase } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { TableSkeleton } from '../components/ui/Skeleton'
import { WorkRow } from '../components/work/PendingWork'
import { byPhase, PHASES } from '../components/work/phase'
import { cn } from '../lib/cn'

/**
 * Trabajos (FS-C): lo que quieres conseguir en todos los proyectos, por fase. Sin filtro, los
 * pendientes (todo menos lo entregado); se filtra por fase, proyecto y repositorio, en la URL.
 */
export function WorkPage() {
  const [params, setParams] = useSearchParams()
  const phase = PHASES.find((p) => p.id === params.get('phase'))?.id ?? null
  const projectId = params.get('project') ?? ''
  const repositoryId = params.get('repo') ?? ''
  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    if (key === 'project') next.delete('repo')
    setParams(next, { replace: true })
  }
  const projects = useQuery({ queryKey: ['projects', undefined], queryFn: () => api.projects() })
  const repos = useQuery({
    queryKey: ['repositories', projectId, undefined],
    queryFn: () => api.repositories(projectId),
    enabled: projectId !== '',
  })
  const items = useQuery({
    queryKey: ['work-items', 'pending', phase, projectId],
    queryFn: () =>
      api.pendingWorkItems({
        phase: phase ? [phase] : undefined,
        projectId: projectId || undefined,
      }),
    refetchInterval: 15_000,
  })
  const visible = (items.data ?? [])
    .filter((w) => !repositoryId || w.repositoryId === repositoryId)
    .sort(byPhase)

  const choosePhase = (p: WorkPhase | null) => set('phase', p)
  return (
    <>
      <div className="page-head">
        <div className="page-title">
          <h1>Trabajos</h1>
          <p className="muted">
            Lo que quieres conseguir. Cada trabajo se consigue con un workflow, y cada ejecución es
            un intento.
          </p>
        </div>
      </div>
      <div className="chips phase-chips work-chips" role="group" aria-label="Fase">
        <button
          type="button"
          className={cn('chip', phase == null && 'chip-on')}
          aria-pressed={phase == null}
          onClick={() => choosePhase(null)}
        >
          Pendientes
        </button>
        {PHASES.map((p) => (
          <button
            key={p.id}
            type="button"
            className={cn('chip', phase === p.id && 'chip-on')}
            aria-pressed={phase === p.id}
            onClick={() => choosePhase(p.id)}
          >
            {p.label}
          </button>
        ))}
      </div>
      <div className="work-filters">
        <label>
          Proyecto
          <select value={projectId} onChange={(e) => set('project', e.target.value || null)}>
            <option value="">Todos</option>
            {(projects.data ?? [])
              .filter((p) => p.archivedAt == null)
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.key} · {p.name}
                </option>
              ))}
          </select>
        </label>
        {projectId !== '' && (repos.data?.length ?? 0) > 1 && (
          <label>
            Repositorio
            <select value={repositoryId} onChange={(e) => set('repo', e.target.value || null)}>
              <option value="">Todos</option>
              {repos.data!.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      <ErrorMessage error={items.error} />
      {items.isPending && <TableSkeleton label="los trabajos" columns={3} rows={4} />}
      {items.data && visible.length === 0 && (
        <EmptyState companion="calm" title="Ningún trabajo con estos filtros." />
      )}
      {visible.length > 0 && (
        <section className="card" aria-label="Trabajos">
          <ul className="work-list">
            {visible.map((w) => (
              <li key={w.id}>
                <WorkRow item={w} showProject={projectId === ''} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}
