import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'

export function WorkItemPage() {
  const { workItemId = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const item = useQuery({
    queryKey: ['work-item', workItemId],
    queryFn: () => api.workItem(workItemId),
  })
  const projectId = item.data?.projectId
  const repos = useQuery({
    queryKey: ['repositories', projectId],
    queryFn: () => api.repositories(projectId!),
    enabled: !!projectId,
  })
  const runs = useQuery({ queryKey: ['runs', workItemId], queryFn: () => api.runs(workItemId) })

  const [repositoryId, setRepositoryId] = useState('')
  const [prompt, setPrompt] = useState('')
  const [maxTurns, setMaxTurns] = useState('')
  const [maxBudgetUsd, setMaxBudgetUsd] = useState('')
  const [timeoutMinutes, setTimeoutMinutes] = useState('')
  const launch = useMutation({
    mutationFn: () =>
      api.launchRun(workItemId, {
        repositoryId: repositoryId || repos.data![0].id,
        prompt,
        maxTurns: optionalNumber(maxTurns),
        maxBudgetUsd: optionalNumber(maxBudgetUsd),
        timeoutMinutes: optionalNumber(timeoutMinutes),
      }),
    onSuccess: (run) => {
      void queryClient.invalidateQueries({ queryKey: ['runs', workItemId] })
      void navigate(`/runs/${run.id}`)
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    launch.mutate()
  }

  return (
    <>
      <p className="breadcrumbs">
        <Link to="/projects">Proyectos</Link> /{' '}
        {projectId && <Link to={`/projects/${projectId}`}>proyecto</Link>} /
      </p>
      <ErrorMessage error={item.error} />
      {item.data && (
        <>
          <h1>
            {item.data.key} · {item.data.title} <StatusBadge status={item.data.status} />
          </h1>
          {item.data.description && <p className="prewrap">{item.data.description}</p>}
        </>
      )}

      <h2>Ejecuciones</h2>
      <ErrorMessage error={runs.error} />
      {runs.data?.length === 0 && <p className="muted">Todavía no se ha lanzado ninguna.</p>}
      <ul className="list">
        {runs.data?.map((r) => (
          <li key={r.id}>
            <Link to={`/runs/${r.id}`}>{formatDateTime(r.createdAt)}</Link>{' '}
            <StatusBadge status={r.status} />
          </li>
        ))}
      </ul>

      <h2>Lanzar agente</h2>
      {repos.data?.length === 0 ? (
        <p className="muted">Registra antes un repositorio en el proyecto.</p>
      ) : (
        <form className="form" onSubmit={submit}>
          <label>
            Repositorio
            <select value={repositoryId} onChange={(e) => setRepositoryId(e.target.value)}>
              {repos.data?.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Prompt
            <textarea
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              rows={6}
              required
            />
          </label>
          <fieldset className="limits">
            <legend>Límites (opcionales; vacío = valor por defecto del servidor)</legend>
            <label>
              Turnos máximos
              <input
                type="number"
                min={1}
                max={1000}
                step={1}
                value={maxTurns}
                onChange={(e) => setMaxTurns(e.target.value)}
              />
            </label>
            <label>
              Presupuesto (US$)
              <input
                type="number"
                min={0.01}
                max={1000}
                step={0.01}
                value={maxBudgetUsd}
                onChange={(e) => setMaxBudgetUsd(e.target.value)}
              />
            </label>
            <label>
              Tiempo máximo (min)
              <input
                type="number"
                min={1}
                max={1440}
                step={1}
                value={timeoutMinutes}
                onChange={(e) => setTimeoutMinutes(e.target.value)}
              />
            </label>
          </fieldset>
          <button type="submit" disabled={launch.isPending || !repos.data?.length}>
            Lanzar
          </button>
          <p className="muted">
            El agente queda en cola hasta que un runner conectado lo recoge (ver README, «Runner
            local»).
          </p>
          <ErrorMessage error={launch.error} />
        </form>
      )}
    </>
  )
}

const optionalNumber = (value: string) => (value.trim() === '' ? undefined : Number(value))
