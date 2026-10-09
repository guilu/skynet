import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, PERMISSION_MODES, type PermissionMode, type Repository } from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'

/** Globs escritos uno por línea; sin ninguno, `undefined` para que el servidor use los suyos. */
function globs(text: string): string[] | undefined {
  const list = lines(text)
  return list.length > 0 ? list : undefined
}

/** Una entrada por línea, sin vacías. */
function lines(text: string): string[] {
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
}

/** Alta de un repositorio del proyecto. Al registrarlo, `onDone` cierra el panel. */
export function RegisterRepositoryForm({
  projectId,
  onDone,
}: {
  projectId: string
  onDone: () => void
}) {
  const queryClient = useQueryClient()
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
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['repositories', projectId] })
      onDone()
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    register.mutate()
  }

  return (
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
        Informes JUnit XML, un glob por línea
        <textarea
          value={reportPaths}
          onChange={(e) => setReportPaths(e.target.value)}
          rows={2}
          placeholder="**/build/test-results/**/*.xml"
        />
        <span className="hint">Vacío: los de Gradle y Maven.</span>
      </label>
      <div className="form-actions">
        <Button type="submit" disabled={register.isPending}>
          {register.isPending ? 'Registrando…' : 'Registrar repositorio'}
        </Button>
      </div>
      <ErrorMessage error={register.error} />
    </form>
  )
}

/**
 * Comando con el que Skynet verifica cada invocación en su worktree, independiente de lo que
 * declare el agente.
 */
export function VerificationForm({
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
    <form className="form" onSubmit={submit}>
      <label>
        Comando de verificación
        <input
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          placeholder="./gradlew test"
        />
        <span className="hint">Vacío: Skynet no verifica las invocaciones.</span>
      </label>
      <label>
        Informes JUnit XML, un glob por línea
        <textarea value={reportPaths} onChange={(e) => setReportPaths(e.target.value)} rows={2} />
      </label>
      <div className="form-actions">
        <Button type="submit" disabled={save.isPending}>
          Guardar verificación
        </Button>
        {save.isSuccess && (
          <span role="status" className="saved">
            Guardado.
          </span>
        )}
      </div>
      <ErrorMessage error={save.error} />
    </form>
  )
}

/**
 * Qué pueden usar y gastar los agentes del repositorio. Sin política propia se usa la global del
 * servidor, que es también el punto de partida del formulario.
 */
export function AgentPolicyForm({
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
    <form className="form" onSubmit={submit}>
      <label>
        Herramientas permitidas, una por línea
        <textarea value={tools} onChange={(e) => setTools(e.target.value)} rows={8} />
        <span className="hint">
          Mejor acotadas: <code>Bash(git:*)</code> en vez de <code>Bash</code>.
        </span>
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
      <label className="switch">
        <input type="checkbox" checked={allEnv} onChange={(e) => setAllEnv(e.target.checked)} />
        <span>
          El agente recibe todas las variables que permite el runner (<code>SKYNET_AGENT_ENV</code>)
        </span>
      </label>
      {!allEnv && (
        <label>
          Variables de entorno que recibe, una por línea
          <textarea value={env} onChange={(e) => setEnv(e.target.value)} rows={2} />
          <span className="hint">Solo llegan las que el runner permite.</span>
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
      <div className="form-actions">
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
          <span role="status" className="saved">
            Guardado.
          </span>
        )}
      </div>
      <ErrorMessage error={save.error ?? inherit.error} />
    </form>
  )
}
