import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Folder } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { api, type Project } from '../api'
import { ArchiveMenu, ArchivedToggle } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { DataTable, EmptyState, type Column } from '../components/list/DataTable'
import { ListToolbar, SearchField } from '../components/list/Toolbar'
import { Button } from '../components/ui/Button'
import { formatDateTime } from '../format'
import { TableSkeleton } from '../components/ui/Skeleton'

const COLUMNS: Column<Project>[] = [
  {
    id: 'project',
    header: 'Proyecto',
    hideable: false,
    cell: (p) => (
      <Link to={`/projects/${p.id}`} className="row-link">
        <span className="key">{p.key}</span> {p.name}
      </Link>
    ),
  },
  { id: 'description', header: 'Descripción', cell: (p) => p.description ?? '—' },
  { id: 'created', header: 'Creado', cell: (p) => formatDateTime(p.createdAt) },
  {
    id: 'actions',
    header: 'Acciones',
    hiddenHeader: true,
    hideable: false,
    cell: (p) => (
      <ArchiveMenu
        size="sm"
        target={{ kind: 'project', id: p.id }}
        name={`${p.key} · ${p.name}`}
        archivedAt={p.archivedAt}
      />
    ),
  },
]

const matches = (p: Project, text: string) =>
  `${p.key} ${p.name} ${p.description ?? ''}`.toLowerCase().includes(text.toLowerCase())

/**
 * Proyectos, con búsqueda por clave, nombre o descripción y el chip «Archivados» (en la URL), y el
 * alta de uno nuevo.
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
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const create = useMutation({
    mutationFn: api.createProject,
    onSuccess: () => {
      setKey('')
      setName('')
      setDescription('')
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
          </ListToolbar>
          <ErrorMessage error={projects.error} />
          {projects.isPending && <TableSkeleton label="los proyectos" columns={3} />}
          {projects.data && (
            <DataTable
              label="Proyectos"
              columns={COLUMNS}
              rows={projects.data.filter((p) => !q || matches(p, q))}
              rowKey={(p) => p.id}
              empty={
                <EmptyState
                  icon={Folder}
                  title={
                    q
                      ? 'Ningún proyecto coincide con la búsqueda.'
                      : archived
                        ? 'No hay proyectos archivados.'
                        : 'Aún no hay proyectos.'
                  }
                >
                  {!q && !archived && <p>Crea el primero con el formulario «Nuevo proyecto».</p>}
                </EmptyState>
              }
            />
          )}
        </section>
        <section className="card" aria-labelledby="new-project">
          <h2 id="new-project">Nuevo proyecto</h2>
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
        </section>
      </div>
    </>
  )
}
