import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Folder, HardDrive, Plus } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { api, type Project } from '../api'
import { ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { EmptyState } from '../components/list/DataTable'
import { ListToolbar, SearchField } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { Sheet } from '../components/ui/Sheet'
import { ProjectRow } from '../components/work/ProjectRow'
import { CardsSkeleton } from '../components/ui/Skeleton'

const matches = (p: Project, text: string) =>
  `${p.key} ${p.name} ${p.description ?? ''}`.toLowerCase().includes(text.toLowerCase())

/**
 * Proyectos, con búsqueda por clave, nombre o descripción y el chip «Archivados» (en la URL). El
 * alta se hace en un panel lateral que abre «Nuevo proyecto». Cada proyecto es una tarjeta ancha
 * con lo que tiene en marcha en pequeñas etiquetas.
 */
export function ProjectsPage() {
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const archived = params.get('archived') === 'true'
  const projects = useQuery({
    queryKey: archived ? ['projects', 'archived'] : ['projects'],
    queryFn: () => api.projects(archived ? 'true' : undefined),
  })
  const setFilters = (next: { q?: string; archived?: boolean }) => {
    const p = new URLSearchParams()
    const text = next.q ?? q
    if (text) p.set('q', text)
    if (next.archived ?? archived) p.set('archived', 'true')
    setParams(p, { replace: true })
  }
  const [creating, setCreating] = useState(false)
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const create = useMutation({
    mutationFn: api.createProject,
    onSuccess: () => {
      setKey('')
      setName('')
      setDescription('')
      setCreating(false)
      void queryClient.invalidateQueries({ queryKey: ['projects'] })
    },
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate({ key, name, description: description || undefined })
  }

  return (
    <>
      <h1>Proyectos</h1>
      <div className="list-with-aside">
        <section aria-label="Lista de proyectos">
          <ListToolbar>
            <SearchField
              label="Buscar proyectos"
              placeholder="Buscar proyecto"
              value={q}
              onChange={(text) => setFilters({ q: text })}
            />
            <ArchivedToggle on={archived} onChange={(on) => setFilters({ archived: on })} />
            <span className="toolbar-end">
              <Button size="sm" onClick={() => setCreating(true)}>
                <Plus size={16} strokeWidth={2.75} aria-hidden="true" />
                Nuevo proyecto
              </Button>
            </span>
          </ListToolbar>
          <ErrorMessage error={projects.error} />
          {projects.isPending && <CardsSkeleton label="los proyectos" count={3} />}
          {projects.data && (
            <ProjectList
              projects={projects.data.filter((p) => !q || matches(p, q))}
              empty={
                <EmptyState
                  icon={Folder}
                  companion={!q && !archived ? 'sleeping' : undefined}
                  title={
                    q
                      ? 'Ningún proyecto coincide con la búsqueda.'
                      : archived
                        ? 'No hay proyectos archivados.'
                        : 'Aún no hay proyectos.'
                  }
                >
                  {!q && !archived && (
                    <p>Impórtalo desde un repositorio clonado o créalo con «Nuevo proyecto».</p>
                  )}
                </EmptyState>
              }
            />
          )}
        </section>
        <div className="projects-aside">
          <section className="card import-choice" aria-labelledby="import-project">
            <span className="import-choice-icon" aria-hidden="true">
              <HardDrive size={22} strokeWidth={2.25} />
            </span>
            <div>
              <h2 id="import-project">Importar desde un repositorio</h2>
              <p className="muted">
                Elige uno de los repositorios clonados en el runner y Skynet rellena el resto.
              </p>
              <Link to="/projects/import" className="btn btn-sm">
                Importar proyecto
              </Link>
            </div>
          </section>
        </div>
      </div>
      <Sheet open={creating} onOpenChange={setCreating} title="Nuevo proyecto">
        <form className="form" onSubmit={submit}>
          <label>
            Clave
            <input
              value={key}
              onChange={(e) => setKey(e.target.value.toUpperCase())}
              placeholder="TKM"
              required
            />
          </label>
          <label>
            Nombre
            <input value={name} onChange={(e) => setName(e.target.value)} required />
          </label>
          <label>
            Descripción
            <input value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <Button type="submit" disabled={create.isPending}>
            Crear proyecto
          </Button>
          <ErrorMessage error={create.error} />
        </form>
      </Sheet>
    </>
  )
}

function ProjectList({ projects, empty }: { projects: Project[]; empty: ReactNode }) {
  if (projects.length === 0) return empty
  return (
    <ul className="proj-rows" aria-label="Proyectos">
      {projects.map((p) => (
        <li key={p.id}>
          <ProjectRow project={p} />
        </li>
      ))}
    </ul>
  )
}
