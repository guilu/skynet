import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Play } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import {
  api,
  type AgentPolicy,
  type InputDefinition,
  type Repository,
  type StagePolicy,
  type WorkflowSummary,
} from '../../api'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'

/** El workflow que se lanza si no se elige otro. */
const ADHOC = 'adhoc'

type Value = string | boolean

/**
 * Lanzamiento de un workflow publicado sobre un trabajo: el workflow (por defecto `adhoc`), sus
 * datos de entrada, el repositorio y los límites, con lo que se le permitirá a cada fase.
 */
export function LaunchForm({
  workItemId,
  repositories,
}: {
  workItemId: string
  repositories: Repository[]
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [workflowKey, setWorkflowKey] = useState(ADHOC)
  const [repositoryId, setRepositoryId] = useState('')
  // Por versión y nombre: cambiar de workflow no arrastra lo escrito en otro.
  const [values, setValues] = useState<Record<string, Value>>({})
  const [maxTurns, setMaxTurns] = useState('')
  const [maxBudgetUsd, setMaxBudgetUsd] = useState('')
  const [timeoutMinutes, setTimeoutMinutes] = useState('')

  const workflows = useQuery({ queryKey: ['workflows'], queryFn: () => api.workflows() })
  const launchable = launchableWorkflows(workflows.data ?? [])
  const workflow = launchable.find((w) => w.key === workflowKey) ?? launchable[0]
  const versionId = workflow?.published?.id
  const version = useQuery({
    queryKey: ['workflow-version', versionId],
    queryFn: () => api.workflowVersion(versionId!),
    enabled: !!versionId,
    // Una versión publicada no cambia.
    staleTime: Infinity,
  })
  const model = version.data?.definition ?? null
  const repository = repositories.find((r) => r.id === repositoryId) ?? repositories[0]
  const policy = repository?.agentPolicy
  const namedAgents = model?.stages.some((s) => s.agent != null) ?? false
  const stagePolicies = useQuery({
    queryKey: ['effective-policy', versionId, repository?.id],
    queryFn: () => api.effectivePolicy(versionId!, repository!.id),
    enabled: !!versionId && !!repository && namedAgents,
  })

  const valueOf = (input: InputDefinition): Value =>
    values[`${versionId}:${input.name}`] ?? initialValue(input)
  const setValue = (input: InputDefinition, value: Value) =>
    setValues((prev) => ({ ...prev, [`${versionId}:${input.name}`]: value }))

  const launch = useMutation({
    mutationFn: () =>
      api.launchRun(workItemId, {
        repositoryId: repository!.id,
        definitionId: versionId,
        inputs: inputsOf(model?.inputs ?? [], valueOf),
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
    <form className="form" onSubmit={submit}>
      <label>
        Workflow
        <select value={workflow?.key ?? ''} onChange={(e) => setWorkflowKey(e.target.value)}>
          {launchable.map((w) => (
            <option key={w.key} value={w.key}>
              {w.name ?? w.key} · v{w.published!.version}
            </option>
          ))}
        </select>
      </label>
      <ErrorMessage error={workflows.error ?? version.error} />
      {workflows.data && launchable.length === 0 && (
        <p className="muted small">No hay workflows publicados.</p>
      )}
      {model?.description && <p className="hint">{model.description}</p>}
      <label>
        Repositorio
        <select value={repository?.id ?? ''} onChange={(e) => setRepositoryId(e.target.value)}>
          {repositories.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </select>
      </label>
      {model?.inputs.map((input) => (
        <InputField
          key={`${versionId}:${input.name}`}
          input={input}
          value={valueOf(input)}
          onChange={(value) => setValue(input, value)}
        />
      ))}
      {policy && (
        <p className="small muted policy-note">
          Política {repository.agentPolicyCustom ? 'del repositorio' : 'global'}: herramientas{' '}
          {policy.allowedTools.length > 0 ? (
            <code>{policy.allowedTools.join(', ')}</code>
          ) : (
            'ninguna'
          )}
          , modo <code>{policy.permissionMode}</code>, entorno {environmentText(policy.environment)}
          .
        </p>
      )}
      {namedAgents && (
        <section aria-label="Política de cada fase" className="stage-policies small">
          <p className="muted">Los agentes del workflow solo pueden recortarla:</p>
          <ErrorMessage error={stagePolicies.error} />
          {stagePolicies.data && (
            <ul>
              {stagePolicies.data.map((p) => (
                <li key={p.stage}>
                  <strong>{p.name ?? p.stage}</strong>
                  {p.agent && <span className="muted"> · agente {p.agent}</span>}: {policyText(p)}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      <LimitsFields
        policy={policy}
        namedAgents={namedAgents}
        maxTurns={[maxTurns, setMaxTurns]}
        maxBudgetUsd={[maxBudgetUsd, setMaxBudgetUsd]}
        timeoutMinutes={[timeoutMinutes, setTimeoutMinutes]}
      />
      <div className="form-actions">
        <Button type="submit" disabled={launch.isPending || !repository || !model}>
          <Play size={18} strokeWidth={2.75} aria-hidden="true" />
          {launch.isPending ? 'Lanzando…' : 'Lanzar'}
        </Button>
      </div>
      <p className="hint">Si no hay ningún runner conectado, mira «Runner local» en el README.</p>
      <ErrorMessage error={launch.error} />
    </form>
  )
}

/** Publicados y sin archivar, con `adhoc` el primero. */
function launchableWorkflows(all: WorkflowSummary[]): WorkflowSummary[] {
  return all
    .filter((w) => w.published != null && w.archivedAt == null)
    .sort((a, b) => Number(b.key === ADHOC) - Number(a.key === ADHOC))
}

/** Un dato de entrada según su tipo; el nombre se muestra con mayúscula inicial. */
function InputField({
  input,
  value,
  onChange,
}: {
  input: InputDefinition
  value: Value
  onChange: (value: Value) => void
}) {
  const label = input.name.charAt(0).toUpperCase() + input.name.slice(1)
  const hintId = `input-${input.name}-hint`
  const describedBy = input.description ? hintId : undefined
  const hint = input.description && (
    <span id={hintId} className="hint">
      {input.description}
    </span>
  )
  if (input.type === 'boolean') {
    return (
      <div className="field">
        <label className="checkbox">
          <input
            type="checkbox"
            checked={value === true}
            onChange={(e) => onChange(e.target.checked)}
            aria-describedby={describedBy}
          />
          {label}
        </label>
        {hint}
      </div>
    )
  }
  const common = {
    value: String(value),
    required: input.required,
    'aria-describedby': describedBy,
  }
  return (
    <div className="field">
      <label>
        {label}
        {input.type === 'text' ? (
          <textarea {...common} rows={7} onChange={(e) => onChange(e.target.value)} />
        ) : (
          <input
            {...common}
            type={input.type === 'number' ? 'number' : 'text'}
            step={input.type === 'number' ? 'any' : undefined}
            onChange={(e) => onChange(e.target.value)}
          />
        )}
      </label>
      {hint}
    </div>
  )
}

function LimitsFields({
  policy,
  namedAgents,
  maxTurns: [maxTurns, setMaxTurns],
  maxBudgetUsd: [maxBudgetUsd, setMaxBudgetUsd],
  timeoutMinutes: [timeoutMinutes, setTimeoutMinutes],
}: {
  policy: AgentPolicy | undefined
  namedAgents: boolean
  maxTurns: [string, (v: string) => void]
  maxBudgetUsd: [string, (v: string) => void]
  timeoutMinutes: [string, (v: string) => void]
}) {
  return (
    <fieldset className="limits">
      <legend>
        Límites (opcionales; vacío = el máximo del repositorio
        {namedAgents && '; los agentes que fijan los suyos usan esos'})
      </legend>
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
  )
}

function initialValue(input: InputDefinition): Value {
  if (input.type === 'boolean') return input.defaultValue === true
  return input.defaultValue == null ? '' : String(input.defaultValue)
}

/** Lo rellenado, con su tipo; lo vacío no se envía y el servidor pone el valor por defecto. */
function inputsOf(
  inputs: InputDefinition[],
  valueOf: (input: InputDefinition) => Value,
): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {}
  for (const input of inputs) {
    const value = valueOf(input)
    if (typeof value === 'boolean') out[input.name] = value
    else if (value.trim() !== '') out[input.name] = input.type === 'number' ? Number(value) : value
  }
  return out
}

function environmentText(environment: string[] | null): string {
  if (environment === null) return 'el que permite el runner'
  return environment.length > 0 ? environment.join(', ') : 'sin variables extra'
}

/** «herramientas Read, Edit; modo plan; 40 turnos, 2 US$, 30 min». */
function policyText(p: StagePolicy): string {
  const parts = [
    `herramientas ${p.allowedTools.length > 0 ? p.allowedTools.join(', ') : 'ninguna'}`,
    `modo ${p.permissionMode ?? 'el del runner'}`,
  ]
  const limits = [
    p.maxTurns != null && `${p.maxTurns} turnos`,
    p.maxBudgetUsd != null && `${p.maxBudgetUsd} US$`,
    p.timeoutMinutes != null && `${p.timeoutMinutes} min`,
  ].filter(Boolean)
  parts.push(limits.length > 0 ? limits.join(', ') : 'límites del lanzamiento')
  return parts.join('; ')
}

const optionalNumber = (value: string) => (value.trim() === '' ? undefined : Number(value))
