import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime } from '../format'
import { Button } from '../components/ui/Button'

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
  const repository = repos.data?.find((r) => r.id === repositoryId) ?? repos.data?.[0]
  const policy = repository?.agentPolicy
  const launch = useMutation({
    mutationFn: () =>
      api.launchRun(workItemId, {
        repositoryId: repository!.id,
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
          {policy && (
            <p className="small muted">
              Política {repository.agentPolicyCustom ? 'del repositorio' : 'global'}: herramientas{' '}
              {policy.allowedTools.length > 0 ? (
                <code>{policy.allowedTools.join(', ')}</code>
              ) : (
                'ninguna'
              )}
              , modo <code>{policy.permissionMode}</code>, entorno{' '}
              {policy.environment === null
                ? 'el que permite el runner'
                : policy.environment.length > 0
                  ? policy.environment.join(', ')
                  : 'sin variables extra'}
              .
            </p>
          )}
          <fieldset className="limits">
            <legend>Límites (opcionales; vacío = el máximo del repositorio)</legend>
            <label>
              Turnos máximos{policy?.maxTurns != null && ` (hasta ${policy.maxTurns})`}
              <input
                type="number"
                min={1}
                max={policy?.maxTurns ?? 1000}
                step={1}
                value={maxTurns}
                onChange={(e) => setMaxTurns(e.target.value)}
              />
            </label>
            <label>
              Presupuesto (US$){policy?.maxBudgetUsd != null && ` (hasta ${policy.maxBudgetUsd})`}
              <input
                type="number"
                min={0.01}
                max={policy?.maxBudgetUsd ?? 1000}
                step={0.01}
                value={maxBudgetUsd}
                onChange={(e) => setMaxBudgetUsd(e.target.value)}
              />
            </label>
            <label>
              Tiempo máximo (min)
              {policy?.timeoutMinutes != null && ` (hasta ${policy.timeoutMinutes})`}
              <input
                type="number"
                min={1}
                max={policy?.timeoutMinutes ?? 1440}
                step={1}
                value={timeoutMinutes}
                onChange={(e) => setTimeoutMinutes(e.target.value)}
              />
            </label>
          </fieldset>
          <Button type="submit" disabled={launch.isPending || !repos.data?.length}>
            Lanzar
          </Button>
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
