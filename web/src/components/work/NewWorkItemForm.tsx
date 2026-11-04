import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, WORK_ITEM_TYPES, type Project, type WorkItem, type WorkItemType } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'

/**
 * Crear un trabajo: lo que quieres conseguir. Desde un proyecto, en ese proyecto; desde Inicio
 * (FS-C), se elige primero el proyecto. Con varios repositorios se elige en cuál se trabaja
 * (FS-B); con uno solo, el servidor lo pone él, y sin elegir se quedará con el del primer
 * lanzamiento.
 */
export function NewWorkItemForm({
  projectId: fixedProject,
  projects = [],
  onDone,
}: {
  /** El proyecto del trabajo; sin él, se elige entre `projects`. */
  projectId?: string
  projects?: Project[]
  onDone: (item: WorkItem) => void
}) {
  const queryClient = useQueryClient()
  const [chosenProject, setChosenProject] = useState(projects[0]?.id ?? '')
  const projectId = fixedProject ?? chosenProject
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<WorkItemType>('FEATURE')
  const [repositoryId, setRepositoryId] = useState('')
  const repos = useQuery({
    queryKey: ['repositories', projectId, undefined],
    queryFn: () => api.repositories(projectId),
    enabled: projectId !== '',
  })
  const active = (repos.data ?? []).filter((r) => r.archivedAt == null)
  const create = useMutation({
    mutationFn: () =>
      api.createWorkItem(projectId, {
        title,
        description: description || undefined,
        type,
        repositoryId: repositoryId || undefined,
      }),
    onSuccess: async (item) => {
      await queryClient.invalidateQueries({ queryKey: ['work-items'] })
      onDone(item)
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <form className="form" onSubmit={submit}>
      {fixedProject == null && (
        <label>
          Proyecto
          <select
            value={chosenProject}
            onChange={(e) => {
              setChosenProject(e.target.value)
              setRepositoryId('')
            }}
            required
          >
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.key} · {p.name}
              </option>
            ))}
          </select>
        </label>
      )}
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
      {active.length > 1 && (
        <label>
          Repositorio
          <select value={repositoryId} onChange={(e) => setRepositoryId(e.target.value)}>
            <option value="">Lo decido al lanzarlo</option>
            {active.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
      )}
      <label>
        Descripción
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={4} />
      </label>
      <div className="form-actions">
        <Button type="submit" disabled={create.isPending || projectId === ''}>
          {create.isPending ? 'Creando…' : 'Crear trabajo'}
        </Button>
      </div>
      <ErrorMessage error={create.error} />
    </form>
  )
}
