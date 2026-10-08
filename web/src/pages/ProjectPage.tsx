import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import {
  api,
  PERMISSION_MODES,
  WORK_ITEM_TYPES,
  type PermissionMode,
  type Repository,
  type WorkItemType,
} from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { StatusBadge } from '../components/StatusBadge'
import { Button } from '../components/ui/Button'

export function ProjectPage() {
  const { projectId = '' } = useParams()
  const project = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId),
  })

  return (
    <>
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
  const [validationCommand, setValidationCommand] = useState('')
  const [reportPaths, setReportPaths] = useState('')
  const register = useMutation({
    mutationFn: () =>
      api.registerRepository(projectId, {
        name,
        localPath,
        defaultBranch: defaultBranch || undefined,
        validationCommand: validationCommand.trim() || undefined,
        testReportPaths: globs(reportPaths),
      }),
    onSuccess: () => {
      setName('')
      setLocalPath('')
      setDefaultBranch('')
      setValidationCommand('')
      setReportPaths('')
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
            <VerificationSettings projectId={projectId} repository={r} />
            <AgentPolicySettings projectId={projectId} repository={r} />
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
        <label>
          Comando de verificación (opcional)
          <input
            value={validationCommand}
            onChange={(e) => setValidationCommand(e.target.value)}
            placeholder="./gradlew test"
          />
        </label>
        <label>
          Informes JUnit XML, un glob por línea (vacío: los de Gradle y Maven)
          <textarea
            value={reportPaths}
            onChange={(e) => setReportPaths(e.target.value)}
            rows={2}
            placeholder="**/build/test-results/**/*.xml"
          />
        </label>
        <Button type="submit" disabled={register.isPending}>
          Registrar repositorio
        </Button>
        <ErrorMessage error={register.error} />
      </form>
    </section>
  )
}

/** Globs escritos uno por línea; sin ninguno, `undefined` para que el servidor use los suyos. */
function globs(text: string): string[] | undefined {
  const list = text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  return list.length > 0 ? list : undefined
}

/**
 * Comando con el que Skynet verifica cada invocación en su worktree, independiente de lo que
 * declare el agente.
 */
function VerificationSettings({
  projectId,
  repository,
}: {
  projectId: string
  repository: Repository
}) {
  const queryClient = useQueryClient()
  const [command, setCommand] = useState(repository.validationCommand ?? '')
  const [reportPaths, setReportPaths] = useState(repository.testReportPaths.join('\n'))
  const save = useMutation({
    mutationFn: () =>
      api.configureVerification(projectId, repository.id, {
        validationCommand: command.trim() || null,
        testReportPaths: globs(reportPaths),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['repositories', projectId] })
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate()
  }

  return (
    <details className="small">
      <summary>
        Verificación:{' '}
        {repository.validationCommand ? (
          <code>{repository.validationCommand}</code>
        ) : (
          <span className="muted">sin comando</span>
        )}
      </summary>
      <form className="form" onSubmit={submit}>
        <label>
          Comando de verificación
          <input
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            placeholder="./gradlew test"
          />
        </label>
        <label>
          Informes JUnit XML, un glob por línea
          <textarea value={reportPaths} onChange={(e) => setReportPaths(e.target.value)} rows={2} />
        </label>
        <Button type="submit" disabled={save.isPending}>
          Guardar verificación
        </Button>
        {save.isSuccess && (
          <span role="status" className="small">
            Guardado.
          </span>
        )}
        <ErrorMessage error={save.error} />
      </form>
    </details>
  )
}

/** Una entrada por línea, sin vacías. */
function lines(text: string): string[] {
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
}

/**
 * Qué pueden usar y gastar los agentes del repositorio. Sin política propia se usa la global del
 * servidor, que es también el punto de partida del formulario.
 */
function AgentPolicySettings({
  projectId,
  repository,
}: {
  projectId: string
  repository: Repository
}) {
  const queryClient = useQueryClient()
  const policy = repository.agentPolicy
  const [tools, setTools] = useState(policy.allowedTools.join('\n'))
  const [mode, setMode] = useState<PermissionMode>(policy.permissionMode)
  const [allEnv, setAllEnv] = useState(policy.environment === null)
  const [env, setEnv] = useState((policy.environment ?? []).join('\n'))
  const [maxTurns, setMaxTurns] = useState(policy.maxTurns?.toString() ?? '')
  const [maxBudgetUsd, setMaxBudgetUsd] = useState(policy.maxBudgetUsd?.toString() ?? '')
  const [timeoutMinutes, setTimeoutMinutes] = useState(policy.timeoutMinutes?.toString() ?? '')
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['repositories', projectId] })
  const save = useMutation({
    mutationFn: () =>
      api.configureAgentPolicy(projectId, repository.id, {
        allowedTools: lines(tools),
        permissionMode: mode,
        environment: allEnv ? null : lines(env),
        maxTurns: maxTurns.trim() === '' ? null : Number(maxTurns),
        maxBudgetUsd: Number(maxBudgetUsd),
        timeoutMinutes: Number(timeoutMinutes),
      }),
    onSuccess: () => void refresh(),
  })
  const inherit = useMutation({
    mutationFn: () => api.inheritAgentPolicy(projectId, repository.id),
    onSuccess: (updated) => {
      const p = updated.agentPolicy
      setTools(p.allowedTools.join('\n'))
      setMode(p.permissionMode)
      setAllEnv(p.environment === null)
      setEnv((p.environment ?? []).join('\n'))
      setMaxTurns(p.maxTurns?.toString() ?? '')
      setMaxBudgetUsd(p.maxBudgetUsd?.toString() ?? '')
      setTimeoutMinutes(p.timeoutMinutes?.toString() ?? '')
      void refresh()
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate()
  }

  return (
    <details className="small">
      <summary>
        Política de agentes: {repository.agentPolicyCustom ? 'propia' : 'global'} ·{' '}
        <code>{policy.permissionMode}</code> ·{' '}
        {policy.allowedTools.join(', ') || 'sin herramientas'}
      </summary>
      <form className="form" onSubmit={submit}>
        <label>
          Herramientas permitidas, una por línea (p. ej. <code>Bash(git:*)</code> en vez de{' '}
          <code>Bash</code>)
          <textarea value={tools} onChange={(e) => setTools(e.target.value)} rows={4} />
        </label>
        <label>
          Modo de permisos
          <select value={mode} onChange={(e) => setMode(e.target.value as PermissionMode)}>
            {PERMISSION_MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={allEnv} onChange={(e) => setAllEnv(e.target.checked)} />
          El agente recibe todas las variables que permite el runner (<code>SKYNET_AGENT_ENV</code>)
        </label>
        {!allEnv && (
          <label>
            Variables de entorno que recibe, una por línea (solo llegan las que el runner permite)
            <textarea value={env} onChange={(e) => setEnv(e.target.value)} rows={2} />
          </label>
        )}
        <fieldset className="limits">
          <legend>Máximos de cada lanzamiento (al lanzar se pueden bajar, no subir)</legend>
          <label>
            Turnos (vacío: sin límite)
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
              required
            />
          </label>
          <label>
            Tiempo (min)
            <input
              type="number"
              min={1}
              max={1440}
              step={1}
              value={timeoutMinutes}
              onChange={(e) => setTimeoutMinutes(e.target.value)}
              required
            />
          </label>
        </fieldset>
        <Button type="submit" disabled={save.isPending}>
          Guardar política
        </Button>
        {repository.agentPolicyCustom && (
          <Button
            type="button"
            variant="secondary"
            onClick={() => inherit.mutate()}
            disabled={inherit.isPending}
          >
            Volver a la política global
          </Button>
        )}
        {(save.isSuccess || inherit.isSuccess) && (
          <span role="status" className="small">
            Guardado.
          </span>
        )}
        <ErrorMessage error={save.error ?? inherit.error} />
      </form>
    </details>
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
        <Button type="submit" disabled={create.isPending}>
          Crear trabajo
        </Button>
        <ErrorMessage error={create.error} />
      </form>
    </section>
  )
}
