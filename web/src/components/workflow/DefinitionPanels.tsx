import { Plus, Trash2 } from 'lucide-react'
import type { Problem } from '../../api'
import { Button } from '../ui/Button'
import {
  addAgent,
  addInput,
  readAgents,
  readInputs,
  readRoot,
  removeAgent,
  removeInput,
  renameAgent,
  renameInput,
  setAgentField,
  setAgentLimit,
  setAgentTools,
  setInputField,
  setRootField,
  type AgentLimit,
} from './definitionEdits'
import { str } from './fieldValues'
import { Field, TextInput, type ApplyEdit } from './StagePanel'

/** Los mensajes de los problemas de una ruta (`agents.dev.model`, `inputs.foco`…) y lo de debajo. */
function messages(problems: Problem[], path: string): string[] {
  return problems
    .filter(
      (p) => p.path === path || p.path.startsWith(`${path}.`) || p.path.startsWith(`${path}[`),
    )
    .map((p) => p.message)
}

const num = (value: unknown) => (typeof value === 'number' ? String(value) : '')

/** El id, el nombre y la descripción del workflow (W8-C). El id solo se cambia en el alta. */
export function WorkflowPanel({
  text,
  problems,
  apply,
  keyEditable,
}: {
  text: string
  problems: Problem[]
  apply: ApplyEdit
  keyEditable: boolean
}) {
  const root = readRoot(text)
  return (
    <section className="card stage-panel" aria-labelledby="workflow-panel-title">
      <h2 id="workflow-panel-title">Workflow</h2>
      <div className="form">
        {keyEditable ? (
          <Field label="Id" errors={messages(problems, 'id')} hint="No cambia entre versiones.">
            {(p) => (
              <TextInput
                {...p}
                value={root.id}
                onCommit={(v) => apply((t) => setRootField(t, 'id', v.trim()))}
              />
            )}
          </Field>
        ) : (
          <p className="muted small">
            Id <code>{root.id}</code>: no cambia entre versiones.
          </p>
        )}
        <Field label="Nombre" errors={messages(problems, 'name')}>
          {(p) => (
            <TextInput
              {...p}
              value={root.name}
              onCommit={(v) => apply((t) => setRootField(t, 'name', v))}
            />
          )}
        </Field>
        <Field label="Descripción" errors={messages(problems, 'description')}>
          {(p) => (
            <TextInput
              {...p}
              multiline
              value={root.description}
              onCommit={(v) => apply((t) => setRootField(t, 'description', v))}
            />
          )}
        </Field>
      </div>
    </section>
  )
}

const PROVIDERS = [
  ['', 'Claude Code (por defecto)'],
  ['codex', 'Codex'],
  ['antigravity', 'Antigravity'],
] as const

const PERMISSIONS = [
  ['', 'El del repositorio'],
  ['dontAsk', 'dontAsk: sin preguntar'],
  ['acceptEdits', 'acceptEdits: acepta ediciones'],
  ['default', 'default'],
  ['plan', 'plan: solo lectura'],
] as const

const LIMITS: [AgentLimit, string, string][] = [
  ['maxTurns', 'Turnos máximos', '1-1000'],
  ['maxBudgetUsd', 'Presupuesto (USD)', '0,01-1000'],
  ['timeoutMinutes', 'Minutos máximos', '1-1440'],
]

/**
 * Los agentes con nombre del workflow (W8-C): prompt, proveedor, modelo, permisos, herramientas y
 * límites. Renombrar uno cambia las fases que lo usan; no se quita uno que se use.
 */
export function AgentsPanel({
  text,
  problems,
  apply,
}: {
  text: string
  problems: Problem[]
  apply: ApplyEdit
}) {
  const agents = readAgents(text)
  return (
    <section className="card stage-panel" aria-labelledby="agents-panel-title">
      <div className="stage-panel-head">
        <h2 id="agents-panel-title">Agentes</h2>
        <Button variant="secondary" size="sm" onClick={() => apply((t) => addAgent(t).text)}>
          <Plus size={16} aria-hidden="true" />
          Añadir agente
        </Button>
      </div>
      {agents.length === 0 && (
        <p className="muted small">
          Sin agentes con nombre: cada fase usa el agente del repositorio con su prompt.
        </p>
      )}
      {agents.map(({ name, fields }) => {
        const path = `agents.${name}`
        const limits = (fields.limits ?? {}) as Record<string, unknown>
        const tools = Array.isArray(fields.tools) ? fields.tools.map(String) : []
        const hasProblems = messages(problems, path).length > 0
        return (
          <details
            key={name}
            className="definition-entry"
            open={agents.length === 1 || hasProblems}
          >
            <summary>
              <code>{name}</code>
              <span className="muted small">
                {str(fields.provider) || 'claude-code'}
                {str(fields.model) && ` · ${str(fields.model)}`}
              </span>
            </summary>
            <div className="form">
              <Field
                label={`Nombre de ${name}`}
                errors={problems.filter((pr) => pr.path === path).map((pr) => pr.message)}
              >
                {(p) => (
                  <TextInput
                    {...p}
                    value={name}
                    onCommit={(v) => apply((t) => renameAgent(t, name, v.trim()))}
                  />
                )}
              </Field>
              <Field label="Prompt" errors={messages(problems, `${path}.prompt`)}>
                {(p) => (
                  <TextInput
                    {...p}
                    multiline
                    value={str(fields.prompt)}
                    onCommit={(v) => apply((t) => setAgentField(t, name, 'prompt', v))}
                  />
                )}
              </Field>
              <Field label="Proveedor" errors={messages(problems, `${path}.provider`)}>
                {(p) => (
                  <select
                    {...p}
                    value={str(fields.provider) === 'claude-code' ? '' : str(fields.provider)}
                    onChange={(e) =>
                      apply((t) => setAgentField(t, name, 'provider', e.target.value || null))
                    }
                  >
                    {PROVIDERS.map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                    {str(fields.provider) &&
                      str(fields.provider) !== 'claude-code' &&
                      !PROVIDERS.some(([v]) => v === str(fields.provider)) && (
                        <option value={str(fields.provider)}>{str(fields.provider)}</option>
                      )}
                  </select>
                )}
              </Field>
              <Field
                label="Modelo"
                errors={messages(problems, `${path}.model`)}
                hint="Vacío, el global. Con Claude Code, p. ej. opus o claude-haiku-4-5."
              >
                {(p) => (
                  <TextInput
                    {...p}
                    value={str(fields.model)}
                    onCommit={(v) => apply((t) => setAgentField(t, name, 'model', v.trim()))}
                  />
                )}
              </Field>
              <Field label="Permisos" errors={messages(problems, `${path}.permissionMode`)}>
                {(p) => (
                  <select
                    {...p}
                    value={str(fields.permissionMode)}
                    onChange={(e) =>
                      apply((t) => setAgentField(t, name, 'permissionMode', e.target.value || null))
                    }
                  >
                    {PERMISSIONS.map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <Field
                label="Herramientas"
                errors={messages(problems, `${path}.tools`)}
                hint="Separadas por comas, p. ej. Read, Grep, Bash(git:*). Vacío, las del repositorio."
              >
                {(p) => (
                  <TextInput
                    {...p}
                    value={tools.join(', ')}
                    onCommit={(v) =>
                      apply((t) =>
                        setAgentTools(
                          t,
                          name,
                          v
                            .split(/,(?![^(]*\))/)
                            .map((tool) => tool.trim())
                            .filter(Boolean),
                        ),
                      )
                    }
                  />
                )}
              </Field>
              <fieldset className="stage-checks">
                <legend>Límites</legend>
                {LIMITS.map(([limit, label, range]) => (
                  <Field
                    key={limit}
                    label={label}
                    errors={messages(problems, `${path}.limits.${limit}`)}
                    hint={range}
                  >
                    {(p) => (
                      <TextInput
                        {...p}
                        inputMode={limit === 'maxBudgetUsd' ? 'decimal' : 'numeric'}
                        value={num(limits[limit])}
                        onCommit={(v) =>
                          apply((t) =>
                            setAgentLimit(
                              t,
                              name,
                              limit,
                              v.trim() === '' ? null : Number(v.replace(',', '.')),
                            ),
                          )
                        }
                      />
                    )}
                  </Field>
                ))}
              </fieldset>
              <div className="form-actions">
                <Button
                  variant="secondary-danger"
                  size="sm"
                  onClick={() => apply((t) => removeAgent(t, name))}
                >
                  <Trash2 size={16} aria-hidden="true" />
                  Quitar {name}
                </Button>
              </div>
            </div>
          </details>
        )
      })}
    </section>
  )
}

const TYPES = [
  ['string', 'Texto corto (string)'],
  ['text', 'Texto largo (text)'],
  ['number', 'Número (number)'],
  ['boolean', 'Sí o no (boolean)'],
] as const

/** Las entradas que se piden al lanzar (W8-C). Renombrar una cambia sus `{{inputs.…}}`. */
export function InputsPanel({
  text,
  problems,
  apply,
}: {
  text: string
  problems: Problem[]
  apply: ApplyEdit
}) {
  const inputs = readInputs(text)
  return (
    <section className="card stage-panel" aria-labelledby="inputs-panel-title">
      <div className="stage-panel-head">
        <h2 id="inputs-panel-title">Entradas</h2>
        <Button variant="secondary" size="sm" onClick={() => apply((t) => addInput(t).text)}>
          <Plus size={16} aria-hidden="true" />
          Añadir entrada
        </Button>
      </div>
      {inputs.length === 0 ? (
        <p className="muted small">Sin entradas: al lanzar no se pide nada.</p>
      ) : (
        <p className="muted small">
          Se piden al lanzar y los prompts las usan como <code>{'{{inputs.<nombre>}}'}</code>.
        </p>
      )}
      {inputs.map(({ name, fields }) => {
        const path = `inputs.${name}`
        const type = str(fields.type) || 'string'
        const fallback =
          fields.default == null
            ? ''
            : typeof fields.default === 'string'
              ? fields.default
              : String(fields.default)
        return (
          <details key={name} className="definition-entry" open>
            <summary>
              <code>{name}</code>
              <span className="muted small">
                {type}
                {fields.required === true && ' · obligatoria'}
              </span>
            </summary>
            <div className="form">
              <Field label={`Nombre de ${name}`}>
                {(p) => (
                  <TextInput
                    {...p}
                    value={name}
                    onCommit={(v) => apply((t) => renameInput(t, name, v.trim()))}
                  />
                )}
              </Field>
              <Field label="Tipo" errors={messages(problems, `${path}.type`)}>
                {(p) => (
                  <select
                    {...p}
                    value={type}
                    onChange={(e) => apply((t) => setInputField(t, name, 'type', e.target.value))}
                  >
                    {TYPES.map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={fields.required === true}
                  onChange={(e) =>
                    apply((t) => setInputField(t, name, 'required', e.target.checked))
                  }
                />
                Obligatoria
              </label>
              <Field label="Valor por defecto" errors={messages(problems, `${path}.default`)}>
                {(p) =>
                  type === 'boolean' ? (
                    <select
                      {...p}
                      value={fallback}
                      onChange={(e) =>
                        apply((t) => setInputField(t, name, 'default', e.target.value || null))
                      }
                    >
                      <option value="">Sin valor</option>
                      <option value="true">Sí</option>
                      <option value="false">No</option>
                    </select>
                  ) : (
                    <TextInput
                      {...p}
                      multiline={type === 'text'}
                      value={fallback}
                      onCommit={(v) => apply((t) => setInputField(t, name, 'default', v))}
                    />
                  )
                }
              </Field>
              <Field label="Descripción" errors={messages(problems, `${path}.description`)}>
                {(p) => (
                  <TextInput
                    {...p}
                    value={str(fields.description)}
                    onCommit={(v) => apply((t) => setInputField(t, name, 'description', v))}
                  />
                )}
              </Field>
              {messages(problems, path).length > 0 && (
                <span className="field-error">{messages(problems, path).join(' ')}</span>
              )}
              <div className="form-actions">
                <Button
                  variant="secondary-danger"
                  size="sm"
                  onClick={() => apply((t) => removeInput(t, name))}
                >
                  <Trash2 size={16} aria-hidden="true" />
                  Quitar {name}
                </Button>
              </div>
            </div>
          </details>
        )
      })}
    </section>
  )
}
