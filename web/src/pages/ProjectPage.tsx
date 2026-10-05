import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { api, WORK_ITEM_TYPES, type WorkItemType } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { StatusBadge } from '../components/StatusBadge'

export function ProjectPage() {
  const { projectId = '' } = useParams()
  const project = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId),
  })

  return (
    <>
      <p className="breadcrumbs">
        <Link to="/projects">Proyectos</Link> /
      </p>
      <ErrorMessage error={project.error} />
      {project.data && (
        <>
          <h1>
            {project.data.key} · {project.data.name}
          </h1>
          {project.data.description && <p className="muted">{project.data.description}</p>}
        </>
      )}
      <div className="columns">
        <Repositories projectId={projectId} />
        <WorkItems projectId={projectId} />
      </div>
    </>
  )
}

function Repositories({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const repos = useQuery({
    queryKey: ['repositories', projectId],
    queryFn: () => api.repositories(projectId),
  })
  const [name, setName] = useState('')
  const [localPath, setLocalPath] = useState('')
  const [defaultBranch, setDefaultBranch] = useState('')
  const register = useMutation({
    mutationFn: () =>
      api.registerRepository(projectId, {
        name,
        localPath,
        defaultBranch: defaultBranch || undefined,
      }),
    onSuccess: () => {
      setName('')
      setLocalPath('')
      setDefaultBranch('')
      void queryClient.invalidateQueries({ queryKey: ['repositories', projectId] })
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    register.mutate()
  }

  return (
    <section>
      <h2>Repositorios</h2>
      <ErrorMessage error={repos.error} />
      {repos.data?.length === 0 && <p className="muted">Ningún repositorio registrado.</p>}
      <ul className="list">
        {repos.data?.map((r) => (
          <li key={r.id}>
            <strong>{r.name}</strong> <code>{r.localPath}</code>{' '}
            <span className="muted">({r.defaultBranch})</span>
          </li>
        ))}
      </ul>
      <form className="form" onSubmit={submit}>
        <label>
          Nombre
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Ruta local (en la máquina del runner)
          <input
            value={localPath}
            onChange={(e) => setLocalPath(e.target.value)}
            placeholder="/home/dev/repos/proyecto"
            required
          />
        </label>
        <label>
          Rama por defecto
          <input
            value={defaultBranch}
            onChange={(e) => setDefaultBranch(e.target.value)}
            placeholder="main"
          />
        </label>
        <button type="submit" disabled={register.isPending}>
          Registrar repositorio
        </button>
        <ErrorMessage error={register.error} />
      </form>
    </section>
  )
}

function WorkItems({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const items = useQuery({
    queryKey: ['work-items', projectId],
    queryFn: () => api.workItems(projectId),
  })
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<WorkItemType>('FEATURE')
  const create = useMutation({
    mutationFn: () =>
      api.createWorkItem(projectId, { title, description: description || undefined, type }),
    onSuccess: () => {
      setTitle('')
      setDescription('')
      void queryClient.invalidateQueries({ queryKey: ['work-items', projectId] })
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <section>
      <h2>Trabajos</h2>
      <ErrorMessage error={items.error} />
      {items.data?.length === 0 && <p className="muted">Ningún trabajo creado.</p>}
      <ul className="list">
        {items.data?.map((w) => (
          <li key={w.id}>
            <Link to={`/work-items/${w.id}`}>
              <strong>{w.key}</strong> {w.title}
            </Link>{' '}
            <StatusBadge status={w.status} />
          </li>
        ))}
      </ul>
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
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} />
        </label>
        <button type="submit" disabled={create.isPending}>
          Crear trabajo
        </button>
        <ErrorMessage error={create.error} />
      </form>
    </section>
  )
}
