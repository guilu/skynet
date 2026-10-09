import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FolderGit2, ListTodo, Plus, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, WORK_ITEM_TYPES, type Repository, type WorkItem, type WorkItemType } from '../api'
import { ArchivedBanner, ArchiveMenu, ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { ListToolbar } from '../components/list/Toolbar'
import {
  AgentPolicyForm,
  RegisterRepositoryForm,
  VerificationForm,
} from '../components/project/RepositoryForms'
import { StatusBadge } from '../components/StatusBadge'
import { Button } from '../components/ui/Button'
import { Sheet } from '../components/ui/Sheet'
import { TabList, TabPanel } from '../components/ui/Tabs'
import { formatDateTime } from '../format'
import { CardsSkeleton, TableSkeleton } from '../components/ui/Skeleton'

type ProjectTab = 'work' | 'repos'
type SheetKind = 'work-item' | 'repository'

const TYPE_LABEL = Object.fromEntries(WORK_ITEM_TYPES.map((t) => [t.value, t.label]))

const WORK_COLUMNS: Column<WorkItem>[] = [
  {
    id: 'work',
    header: 'Trabajo',
    hideable: false,
    cell: (w) => (
      <Link to={`/work-items/${w.id}`} className="row-link">
        <span className="key">{w.key}</span> {w.title}
      </Link>
    ),
  },
  { id: 'type', header: 'Tipo', cell: (w) => TYPE_LABEL[w.type] ?? w.type },
  { id: 'status', header: 'Estado', cell: (w) => <StatusBadge status={w.status} /> },
  { id: 'created', header: 'Creado', cell: (w) => formatDateTime(w.createdAt) },
  {
    id: 'actions',
    header: 'Acciones',
    hiddenHeader: true,
    hideable: false,
    cell: (w) => (
      <ArchiveMenu
        size="sm"
        target={{ kind: 'work-item', id: w.id }}
        name={`${w.key} · ${w.title}`}
        archivedAt={w.archivedAt}
      />
    ),
  },
]

/**
 * Proyecto: sus trabajos y sus repositorios en pestañas (la elegida y el chip «Archivados», en la
 * URL). Crear un trabajo, registrar un repositorio y configurar su verificación o su política se
 * hace en un panel lateral. Archivado, es de solo lectura: solo se puede restaurar o eliminar.
 */
export function ProjectPage() {
  const { projectId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab: ProjectTab = params.get('tab') === 'repos' ? 'repos' : 'work'
  const showArchived = params.get('archived') === 'true'
  const navigate = useNavigate()
  const [sheet, setSheet] = useState<SheetKind | null>(null)
  const project = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId),
  })
  const filter = showArchived ? 'true' : undefined
  const items = useQuery({
    queryKey: ['work-items', projectId, filter],
    queryFn: () => api.workItems(projectId, filter),
  })
  const repos = useQuery({
    queryKey: ['repositories', projectId, filter],
    queryFn: () => api.repositories(projectId, filter),
  })
  const setView = (next: { tab?: ProjectTab; archived?: boolean }) => {
    const p = new URLSearchParams()
    if ((next.tab ?? tab) === 'repos') p.set('tab', 'repos')
    if (next.archived ?? showArchived) p.set('archived', 'true')
    setParams(p, { replace: true })
  }
  const selectTab = (next: ProjectTab) => setView({ tab: next })
  const archivedAt = project.data?.archivedAt ?? null
  const editable = !!project.data && archivedAt == null
  const closeSheet = () => setSheet(null)

  return (
    <>
      <ErrorMessage error={project.error} />
      <div className="page-head">
        <div className="page-title">
          <h1>{project.data ? `${project.data.key} · ${project.data.name}` : 'Proyecto'}</h1>
          {project.data?.description && <p className="muted">{project.data.description}</p>}
        </div>
        <div className="page-actions">
          {editable && (
            <>
              <Button variant="secondary" onClick={() => setSheet('repository')}>
                <FolderGit2 size={18} strokeWidth={2.5} aria-hidden="true" />
                Nuevo repositorio
              </Button>
              <Button onClick={() => setSheet('work-item')}>
                <Plus size={18} strokeWidth={2.75} aria-hidden="true" />
                Nuevo trabajo
              </Button>
            </>
          )}
          {project.data && (
            <ArchiveMenu
              target={{ kind: 'project', id: projectId }}
              name={`${project.data.key} · ${project.data.name}`}
              archivedAt={archivedAt}
              onDeleted={() => void navigate('/projects')}
            />
          )}
        </div>
      </div>
      {archivedAt && (
        <ArchivedBanner
          target={{ kind: 'project', id: projectId }}
          archivedAt={archivedAt}
          note="Sus trabajos y ejecuciones no salen en las listas, y no se pueden crear trabajos ni lanzar agentes."
        />
      )}

      <TabList
        label="Secciones del proyecto"
        tabs={[
          { id: 'work', label: 'Trabajos', icon: ListTodo, count: items.data?.length },
          { id: 'repos', label: 'Repositorios', icon: FolderGit2, count: repos.data?.length },
        ]}
        selected={tab}
        onSelect={selectTab}
        idPrefix="project-tab"
        panelId="project-panel"
      />
      <TabPanel id="project-panel" labelledBy={`project-tab-${tab}`} className="tab-body">
        <ListToolbar>
          <ArchivedToggle on={showArchived} onChange={(on) => setView({ archived: on })} />
        </ListToolbar>
        {tab === 'work' ? (
          <>
            <ErrorMessage error={items.error} />
            {items.isPending && <TableSkeleton label="los trabajos" />}
            {items.data && (
              <DataTable
                label="Trabajos"
                columns={WORK_COLUMNS}
                rows={items.data}
                rowKey={(w) => w.id}
                empty={
                  showArchived ? (
                    <EmptyState icon={ListTodo} title="No hay trabajos archivados." />
                  ) : (
                    <EmptyState icon={ListTodo} title="Aún no hay trabajos.">
                      <p>Un trabajo agrupa las ejecuciones de agentes sobre una misma tarea.</p>
                      {editable && (
                        <Button size="sm" onClick={() => setSheet('work-item')}>
                          Crear el primero
                        </Button>
                      )}
                    </EmptyState>
                  )
                }
              />
            )}
          </>
        ) : (
          <>
            <ErrorMessage error={repos.error} />
            {repos.isPending && <CardsSkeleton label="los repositorios" />}
            {repos.data?.length === 0 &&
              (showArchived ? (
                <EmptyState icon={FolderGit2} title="No hay repositorios archivados." />
              ) : (
                <EmptyState icon={FolderGit2} title="Ningún repositorio registrado.">
                  <p>
                    Los agentes trabajan en un worktree del repositorio, en la máquina del runner.
                  </p>
                  {editable && (
                    <Button size="sm" onClick={() => setSheet('repository')}>
                      Registrar el primero
                    </Button>
                  )}
                </EmptyState>
              ))}
            <div className="repo-grid">
              {repos.data?.map((r) => (
                <RepositoryCard
                  key={r.id}
                  projectId={projectId}
                  repository={r}
                  editable={editable && r.archivedAt == null}
                />
              ))}
            </div>
          </>
        )}
      </TabPanel>

      <Sheet
        open={sheet === 'work-item'}
        onOpenChange={(o) => !o && closeSheet()}
        title="Nuevo trabajo"
      >
        <NewWorkItemForm
          projectId={projectId}
          onDone={() => {
            closeSheet()
            selectTab('work')
          }}
        />
      </Sheet>
      <Sheet
        open={sheet === 'repository'}
        onOpenChange={(o) => !o && closeSheet()}
        title="Registrar repositorio"
        description="El repositorio tiene que estar clonado en la máquina del runner."
      >
        <RegisterRepositoryForm
          projectId={projectId}
          onDone={() => {
            closeSheet()
            selectTab('repos')
          }}
        />
      </Sheet>
    </>
  )
}

/** Un repositorio: dónde está, cómo se verifica y qué pueden hacer sus agentes. */
function RepositoryCard({
  projectId,
  repository: r,
  editable,
}: {
  projectId: string
  repository: Repository
  /** Ni el repositorio ni su proyecto están archivados. */
  editable: boolean
}) {
  const [sheet, setSheet] = useState<'verification' | 'policy' | null>(null)
  const policy = r.agentPolicy
  const limits = [
    policy.maxTurns != null ? `${policy.maxTurns} turnos` : 'turnos sin límite',
    `${policy.maxBudgetUsd} US$`,
    `${policy.timeoutMinutes} min`,
  ].join(' · ')
  const close = (open: boolean) => !open && setSheet(null)

  return (
    <article className="card repo-card" aria-labelledby={`repo-${r.id}`}>
      <header className="repo-head">
        <span className="repo-icon" aria-hidden="true">
          <FolderGit2 size={22} strokeWidth={2.25} />
        </span>
        <div className="repo-title">
          <h2 id={`repo-${r.id}`}>{r.name}</h2>
          <code className="repo-path">{r.localPath}</code>
        </div>
        <span className="tag">rama {r.defaultBranch}</span>
        {r.archivedAt && <span className="tag">archivado</span>}
        <ArchiveMenu
          size="sm"
          target={{ kind: 'repository', id: r.id, projectId }}
          name={r.name}
          archivedAt={r.archivedAt}
        />
      </header>

      <section className="repo-section" aria-label={`Verificación de ${r.name}`}>
        <div className="repo-section-head">
          <h3>
            <ShieldCheck size={18} strokeWidth={2.5} aria-hidden="true" /> Verificación
          </h3>
          {editable && (
            <Button
              size="sm"
              variant="secondary"
              aria-label={`Configurar verificación de ${r.name}`}
              onClick={() => setSheet('verification')}
            >
              Configurar verificación
            </Button>
          )}
        </div>
        {r.validationCommand ? (
          <p className="small">
            <code>{r.validationCommand}</code>
            {r.testReportPaths.length > 0 && (
              <span className="muted"> · informes {r.testReportPaths.join(', ')}</span>
            )}
          </p>
        ) : (
          <p className="muted small">Sin comando: las invocaciones no se verifican.</p>
        )}
      </section>

      <section className="repo-section" aria-label={`Política de agentes de ${r.name}`}>
        <div className="repo-section-head">
          <h3>
            <SlidersHorizontal size={18} strokeWidth={2.5} aria-hidden="true" /> Política de agentes{' '}
            <span className={r.agentPolicyCustom ? 'tag tag-on' : 'tag'}>
              {r.agentPolicyCustom ? 'propia' : 'global'}
            </span>
          </h3>
          {editable && (
            <Button
              size="sm"
              variant="secondary"
              aria-label={`Editar política de ${r.name}`}
              onClick={() => setSheet('policy')}
            >
              Editar política
            </Button>
          )}
        </div>
        <dl className="repo-facts small">
          <div>
            <dt>Modo</dt>
            <dd>
              <code>{policy.permissionMode}</code>
            </dd>
          </div>
          <div>
            <dt>Herramientas</dt>
            <dd className="tool-chips">
              {policy.allowedTools.length > 0
                ? policy.allowedTools.map((t) => (
                    <code key={t} className="tool-chip">
                      {t}
                    </code>
                  ))
                : 'ninguna'}
            </dd>
          </div>
          <div>
            <dt>Entorno</dt>
            <dd>
              {policy.environment === null
                ? 'el que permite el runner'
                : policy.environment.join(', ') || 'sin variables extra'}
            </dd>
          </div>
          <div>
            <dt>Máximos</dt>
            <dd>{limits}</dd>
          </div>
        </dl>
      </section>

      <Sheet
        open={sheet === 'verification'}
        onOpenChange={close}
        title={`Verificación de ${r.name}`}
        description="Skynet ejecuta este comando en el worktree de cada invocación completada, aparte de lo que diga el agente."
      >
        <VerificationForm projectId={projectId} repository={r} />
      </Sheet>
      <Sheet
        open={sheet === 'policy'}
        onOpenChange={close}
        title={`Política de agentes de ${r.name}`}
        description={
          r.agentPolicyCustom
            ? 'Este repositorio tiene política propia.'
            : 'Este repositorio usa la política global del servidor; al guardar, pasa a tener la suya.'
        }
      >
        <AgentPolicyForm projectId={projectId} repository={r} />
      </Sheet>
    </article>
  )
}

function NewWorkItemForm({ projectId, onDone }: { projectId: string; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<WorkItemType>('FEATURE')
  const create = useMutation({
    mutationFn: () =>
      api.createWorkItem(projectId, { title, description: description || undefined, type }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['work-items', projectId] })
      onDone()
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <form className="form" onSubmit={submit}>
      <label>
        Título
        <input value={title} onChange={(e) => setTitle(e.target.value)} required />
      </label>
      <label>
        Tipo
        <select value={type} onChange={(e) => setType(e.target.value as WorkItemType)}>
          {WORK_ITEM_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Descripción
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={4} />
      </label>
      <div className="form-actions">
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? 'Creando…' : 'Crear trabajo'}
        </Button>
      </div>
      <ErrorMessage error={create.error} />
    </form>
  )
}
