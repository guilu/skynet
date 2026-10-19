import { Code, Plus, Trash2, X } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import type { Problem } from '../../api'
import { Button } from '../ui/Button'
import {
  addChild,
  addDependency,
  NAMED_CHECKS,
  readAgentNames,
  readChildIds,
  readStage,
  readStages,
  removeChild,
  removeDependency,
  renameStage,
  setChildField,
  setDependencyOptional,
  setNamedChecks,
  setRetryAttempts,
  setStageField,
  type StageField,
} from './definitionEdits'
import { str } from './fieldValues'
import { ProblemList } from './ProblemList'

/** Aplica un cambio al texto; si no se puede, lo dice y devuelve false. */
export type ApplyEdit = (edit: (text: string) => string) => boolean

const PHASES = [
  ['spec', 'Especificación'],
  ['plan', 'Planificación'],
  ['implement', 'Implementación'],
  ['test', 'Tests'],
  ['review', 'Revisión'],
  ['deliver', 'Entrega'],
] as const

/** Los tipos con worktree propio: los que pueden llevar `workspace`, `retry`… */
const WITH_WORKTREE = new Set(['agent', 'command', 'parallel'])

/** Los mensajes de los problemas de un campo de la fase (`stages.<id>.<campo>…`). */
function fieldMessages(problems: Problem[], id: string, field: string): string[] {
  const prefix = `stages.${id}.${field}`
  return problems
    .filter(
      (p) =>
        p.path === prefix || p.path.startsWith(`${prefix}.`) || p.path.startsWith(`${prefix}[`),
    )
    .map((p) => p.message)
}

/** Etiqueta, control y, si los hay, los problemas del campo debajo. */
export function Field({
  label,
  errors = [],
  hint,
  children,
}: {
  label: string
  errors?: string[]
  hint?: ReactNode
  children: (props: { id: string; 'aria-invalid'?: true; 'aria-describedby'?: string }) => ReactNode
}) {
  const id = useId()
  const described = [hint && `${id}-hint`, errors.length > 0 && `${id}-errors`]
    .filter(Boolean)
    .join(' ')
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children({
        id,
        ...(errors.length > 0 && { 'aria-invalid': true }),
        ...(described && { 'aria-describedby': described }),
      })}
      {hint && (
        <span id={`${id}-hint`} className="hint">
          {hint}
        </span>
      )}
      {errors.length > 0 && (
        <span id={`${id}-errors`} className="field-error">
          {errors.join(' ')}
        </span>
      )}
    </div>
  )
}

/**
 * Un texto que se aplica al salir del campo (o con Intro, si es de una línea): así cada cambio es
 * una sola edición del YAML, que se deshace de una vez.
 */
export function TextInput({
  value,
  onCommit,
  multiline = false,
  ...props
}: {
  value: string
  onCommit: (value: string) => void
  multiline?: boolean
  id: string
  placeholder?: string
  inputMode?: 'numeric' | 'decimal'
  'aria-invalid'?: true
  'aria-describedby'?: string
}) {
  const [draft, setDraft] = useState(value)
  // Si el texto cambia por otro lado (deshacer, el YAML), el campo enseña lo nuevo.
  const [base, setBase] = useState(value)
  if (base !== value) {
    setBase(value)
    setDraft(value)
  }
  const commit = () => {
    if (draft !== value) onCommit(draft)
  }
  if (multiline)
    return (
      <textarea
        {...props}
        value={draft}
        rows={5}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
      />
    )
  return (
    <input
      {...props}
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          commit()
        } else if (e.key === 'Escape') setDraft(value)
      }}
    />
  )
}

interface Props {
  text: string
  id: string
  problems: Problem[]
  apply: ApplyEdit
  /** La fase ha cambiado de id (renombrada). */
  onRenamed: (id: string) => void
  /** Pide confirmación para borrarla. */
  onDelete: () => void
  /** Salta a ese campo (o a la fase) en la pestaña YAML. */
  onEditYaml: (field?: string) => void
  onRevealProblem: (line: number, column: number) => void
}

/**
 * Las propiedades de una fase, según su tipo (W8-B). Lee el texto tal cual (no espera a la
 * validación) y cada cambio es una edición del YAML. Lo poco común (`outputSchema`, `artifacts`,
 * `scope`…) se edita en el YAML.
 */
export function StagePanel({
  text,
  id,
  problems,
  apply,
  onRenamed,
  onDelete,
  onEditYaml,
  onRevealProblem,
}: Props) {
  const stage = readStage(text, id)
  const [newDep, setNewDep] = useState('')
  const [newChildAgent, setNewChildAgent] = useState('')
  if (!stage) return null
  const type = str(stage.type)
  const agents = readAgentNames(text)
  const stages = readStages(text)
  const deps = stages.find((s) => s.id === id)?.dependsOn ?? []
  const own = problems.filter(
    (p) =>
      p.path === `stages.${id}` ||
      p.path.startsWith(`stages.${id}.`) ||
      p.path.startsWith(`stages.${id}[`),
  )
  const errors = (field: string) => fieldMessages(problems, id, field)
  const set = (field: StageField, value: string | null) =>
    apply((t) => setStageField(t, id, field, value === '' ? null : value))
  const candidates = stages.filter((s) => s.id !== id && !deps.some((d) => d.stage === s.id))
  // `workspaceFrom`: una dependencia o, si es una `parallel`, uno de sus hijos.
  const workspaceOptions = deps.flatMap((d) => [
    d.stage,
    ...readChildIds(text, d.stage).map((c) => `${d.stage}.${c}`),
  ])
  const checks = Array.isArray(stage.checks) ? stage.checks : []
  const retry = stage.retry as { maxAttempts?: unknown } | undefined
  const children = type === 'parallel' ? readChildIds(text, id) : []
  const rawChildren = Array.isArray(stage.children)
    ? (stage.children as Record<string, unknown>[])
    : []
  const agentStages = stages
    .filter((s) => s.id !== id && readStage(text, s.id)?.type === 'agent')
    .map((s) => s.id)

  return (
    <section className="card stage-panel" aria-labelledby="stage-panel-title">
      <div className="stage-panel-head">
        <h2 id="stage-panel-title">
          Fase <code>{id}</code>
        </h2>
        <span className="tag">{type}</span>
      </div>
      <div className="form">
        <Field label="Id" errors={errors('id')} hint="Al cambiarlo se actualiza lo que la nombra.">
          {(p) => (
            <TextInput
              {...p}
              value={id}
              onCommit={(next) => {
                if (apply((t) => renameStage(t, id, next.trim()))) onRenamed(next.trim())
              }}
            />
          )}
        </Field>
        <Field label="Nombre" errors={errors('name')}>
          {(p) => <TextInput {...p} value={str(stage.name)} onCommit={(v) => set('name', v)} />}
        </Field>

        {type === 'agent' && (
          <>
            <Field label="Agente" errors={errors('agent')}>
              {(p) => (
                <select
                  {...p}
                  value={str(stage.agent)}
                  onChange={(e) => set('agent', e.target.value)}
                >
                  <option value="">Agente del repositorio</option>
                  {agents.map((a) => (
                    <option key={a} value={a}>
                      {a}
                    </option>
                  ))}
                  {str(stage.agent) && !agents.includes(str(stage.agent)) && (
                    <option value={str(stage.agent)}>{str(stage.agent)} (no existe)</option>
                  )}
                </select>
              )}
            </Field>
            <Field label="Prompt" errors={errors('prompt')}>
              {(p) => (
                <TextInput
                  {...p}
                  multiline
                  value={str(stage.prompt)}
                  onCommit={(v) => set('prompt', v)}
                />
              )}
            </Field>
          </>
        )}

        {type === 'command' && (
          <Field
            label="Comando"
            errors={errors('command')}
            hint="Se ejecuta con sh -c en el worktree."
          >
            {(p) => (
              <TextInput
                {...p}
                value={str(stage.command)}
                placeholder="./gradlew test"
                onCommit={(v) => apply((t) => setStageField(t, id, 'command', v))}
              />
            )}
          </Field>
        )}

        {type === 'human-approval' && (
          <>
            <Field label="Qué se aprueba" errors={errors('action')}>
              {(p) => (
                <TextInput
                  {...p}
                  multiline
                  value={str(stage.action)}
                  onCommit={(v) => apply((t) => setStageField(t, id, 'action', v))}
                />
              )}
            </Field>
            <Field label="Riesgo" errors={errors('risk')}>
              {(p) => (
                <select
                  {...p}
                  value={str(stage.risk)}
                  onChange={(e) => set('risk', e.target.value)}
                >
                  <option value="">Medio (por defecto)</option>
                  <option value="low">Bajo</option>
                  <option value="medium">Medio</option>
                  <option value="high">Alto</option>
                </select>
              )}
            </Field>
            <Field
              label="Corrige al pedir cambios"
              errors={errors('revise')}
              hint="Sin elegir, su única dependencia agent."
            >
              {(p) => (
                <select
                  {...p}
                  value={str(stage.revise)}
                  onChange={(e) => set('revise', e.target.value)}
                >
                  <option value="">Por defecto</option>
                  {agentStages.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          </>
        )}

        <Field
          label={type === 'conditional' ? 'Condición' : 'Solo si'}
          errors={errors('condition')}
          hint={type === 'conditional' ? undefined : 'Opcional, p. ej. review.hasBlockingFindings'}
        >
          {(p) => (
            <TextInput {...p} value={str(stage.condition)} onCommit={(v) => set('condition', v)} />
          )}
        </Field>

        <Field label="Macrofase (carril)" errors={errors('phase')}>
          {(p) => (
            <select {...p} value={str(stage.phase)} onChange={(e) => set('phase', e.target.value)}>
              <option value="">Sin macrofase</option>
              {PHASES.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>

        {WITH_WORKTREE.has(type) && (
          <Field label="Worktree" errors={errors('workspace')}>
            {(p) => (
              <select
                {...p}
                value={str(stage.workspace)}
                onChange={(e) => set('workspace', e.target.value)}
              >
                <option value="">Continúa el de su dependencia (por defecto)</option>
                <option value="isolated-worktree">Uno nuevo desde la rama por defecto</option>
              </select>
            )}
          </Field>
        )}
        {(WITH_WORKTREE.has(type) || type === 'verification') && workspaceOptions.length > 0 && (
          <Field
            label="Continúa el worktree de"
            errors={errors('workspaceFrom')}
            hint="Obligatorio si depende de varias fases con worktree."
          >
            {(p) => (
              <select
                {...p}
                value={str(stage.workspaceFrom)}
                onChange={(e) => set('workspaceFrom', e.target.value)}
              >
                <option value="">Sin elegir</option>
                {workspaceOptions.map((w) => (
                  <option key={w} value={w}>
                    {w}
                  </option>
                ))}
                {str(stage.workspaceFrom) &&
                  !workspaceOptions.includes(str(stage.workspaceFrom)) && (
                    <option value={str(stage.workspaceFrom)}>{str(stage.workspaceFrom)}</option>
                  )}
              </select>
            )}
          </Field>
        )}

        {(type === 'agent' || type === 'command') && (
          <Field
            label="Intentos"
            errors={errors('retry')}
            hint="En total, del 1 al 5; vacío, sin reintentos."
          >
            {(p) => (
              <TextInput
                {...p}
                value={
                  retry && typeof retry.maxAttempts === 'number' ? String(retry.maxAttempts) : ''
                }
                onCommit={(v) =>
                  apply((t) => setRetryAttempts(t, id, v.trim() === '' ? null : Number(v)))
                }
              />
            )}
          </Field>
        )}
        <Field label="Tiempo máximo" errors={errors('timeout')} hint="Con s, m o h: 30m.">
          {(p) => (
            <TextInput {...p} value={str(stage.timeout)} onCommit={(v) => set('timeout', v)} />
          )}
        </Field>

        {(WITH_WORKTREE.has(type) || type === 'verification') && (
          <fieldset className="stage-checks">
            <legend>Comprobaciones</legend>
            {NAMED_CHECKS.map((name) => (
              <label key={name} className="checkbox">
                <input
                  type="checkbox"
                  checked={checks.includes(name)}
                  onChange={(e) =>
                    apply((t) =>
                      setNamedChecks(
                        t,
                        id,
                        e.target.checked
                          ? [...checks.filter((c): c is string => typeof c === 'string'), name]
                          : checks.filter((c): c is string => typeof c === 'string' && c !== name),
                      ),
                    )
                  }
                />
                {name}
              </label>
            ))}
            {errors('checks').length > 0 && (
              <span className="field-error">{errors('checks').join(' ')}</span>
            )}
          </fieldset>
        )}
      </div>

      {type === 'parallel' && (
        <div className="stage-panel-section">
          <h3>Hijos</h3>
          {children.length === 0 && <p className="muted small">Aún no tiene ninguno.</p>}
          <ul className="stage-panel-list">
            {children.map((child, i) => {
              const raw = rawChildren[i] ?? {}
              return (
                <li key={child}>
                  <code>{child}</code>
                  <label className="visually-hidden" htmlFor={`child-agent-${child}`}>
                    Agente de {child}
                  </label>
                  <select
                    id={`child-agent-${child}`}
                    value={str(raw.agent)}
                    onChange={(e) =>
                      apply((t) => setChildField(t, id, child, 'agent', e.target.value))
                    }
                  >
                    {agents.map((a) => (
                      <option key={a} value={a}>
                        {a}
                      </option>
                    ))}
                    {str(raw.agent) && !agents.includes(str(raw.agent)) && (
                      <option value={str(raw.agent)}>{str(raw.agent)} (no existe)</option>
                    )}
                  </select>
                  <Button
                    variant="secondary"
                    size="sm"
                    aria-label={`Quitar el hijo ${child}`}
                    onClick={() => apply((t) => removeChild(t, id, child))}
                  >
                    <X size={16} aria-hidden="true" />
                  </Button>
                </li>
              )
            })}
          </ul>
          {agents.length > 0 ? (
            <div className="stage-panel-add">
              <label className="visually-hidden" htmlFor="new-child-agent">
                Agente del hijo nuevo
              </label>
              <select
                id="new-child-agent"
                value={newChildAgent || agents[0]}
                onChange={(e) => setNewChildAgent(e.target.value)}
              >
                {agents.map((a) => (
                  <option key={a} value={a}>
                    {a}
                  </option>
                ))}
              </select>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => apply((t) => addChild(t, id, newChildAgent || agents[0]).text)}
              >
                <Plus size={16} aria-hidden="true" />
                Añadir hijo
              </Button>
            </div>
          ) : (
            <p className="muted small">Define agentes en el YAML para añadir hijos.</p>
          )}
        </div>
      )}

      <div className="stage-panel-section">
        <h3>Depende de</h3>
        {deps.length === 0 && <p className="muted small">De ninguna: arranca al principio.</p>}
        <ul className="stage-panel-list">
          {deps.map((d) => (
            <li key={d.stage}>
              <code>{d.stage}</code>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={d.optional}
                  onChange={(e) =>
                    apply((t) => setDependencyOptional(t, id, d.stage, e.target.checked))
                  }
                />
                Opcional
              </label>
              <Button
                variant="secondary"
                size="sm"
                aria-label={`Quitar la dependencia de ${d.stage}`}
                onClick={() => apply((t) => removeDependency(t, id, d.stage))}
              >
                <X size={16} aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
        {candidates.length > 0 && (
          <div className="stage-panel-add">
            <label className="visually-hidden" htmlFor="new-dependency">
              Fase de la que dependerá
            </label>
            <select id="new-dependency" value={newDep} onChange={(e) => setNewDep(e.target.value)}>
              <option value="">Elige una fase…</option>
              {candidates.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.id}
                </option>
              ))}
            </select>
            <Button
              variant="secondary"
              size="sm"
              disabled={!newDep}
              onClick={() => {
                if (apply((t) => addDependency(t, id, newDep))) setNewDep('')
              }}
            >
              <Plus size={16} aria-hidden="true" />
              Añadir dependencia
            </Button>
          </div>
        )}
      </div>

      {own.length > 0 && (
        <div className="stage-panel-section">
          <h3>Sus problemas</h3>
          <ProblemList problems={own} onSelect={onRevealProblem} />
        </div>
      )}

      <div className="form-actions">
        <Button variant="secondary" size="sm" onClick={() => onEditYaml()}>
          <Code size={16} aria-hidden="true" />
          Editar en YAML
        </Button>
        <Button variant="secondary-danger" size="sm" onClick={onDelete}>
          <Trash2 size={16} aria-hidden="true" />
          Borrar fase…
        </Button>
      </div>
    </section>
  )
}
