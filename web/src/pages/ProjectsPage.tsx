import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'

export function ProjectsPage() {
  const queryClient = useQueryClient()
  const projects = useQuery({ queryKey: ['projects'], queryFn: api.projects })
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
      <ErrorMessage error={projects.error} />
      {projects.data?.length === 0 && <p className="muted">Aún no hay proyectos.</p>}
      <ul className="list">
        {projects.data?.map((p) => (
          <li key={p.id}>
            <Link to={`/projects/${p.id}`}>
              <strong>{p.key}</strong> {p.name}
            </Link>
            {p.description && <span className="muted"> — {p.description}</span>}
          </li>
        ))}
      </ul>

      <h2>Nuevo proyecto</h2>
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
        <button type="submit" disabled={create.isPending}>
          Crear proyecto
        </button>
        <ErrorMessage error={create.error} />
      </form>
    </>
  )
}
