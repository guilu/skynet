import type { RetryPolicy, VerificationCheck, WorkflowModel } from '../../api'
import { formatDuration, providerName } from '../../format'
import { RISK_LABEL } from '../approval/approvalFormat'
import { formatSeconds } from '../run/stageNotes'

const WORKSPACE = { INHERIT: 'Continúa el de su dependencia', ISOLATED: 'Uno nuevo' } as const
const WITH_WORKSPACE = ['agent', 'command', 'parallel']

/** Un check en una línea: `artifacts docs/spec.md`, `command npm run lint`, `diff`. */
function checkText(check: VerificationCheck): string {
  const target = check.command ?? check.paths.join(', ')
  return target ? `${check.name} ${target}` : check.name
}

/** «hasta 3 intentos si falla por lost, start o error; espera 30 s» */
function retryText(retry: RetryPolicy): string {
  const on = retry.on.join(', ').replace(/, ([^,]*)$/, ' o $1')
  return `hasta ${retry.maxAttempts} intentos si falla por ${on}; espera ${formatDuration(
    retry.delaySeconds * 1000,
  )}`
}

function workspaceLabel(stage: WorkflowModel['stages'][number]): string {
  if (!WITH_WORKSPACE.includes(stage.type)) return '—'
  if (stage.type === 'parallel') {
    if (stage.workspace === 'ISOLATED' || stage.dependsOn.length === 0) return 'Uno nuevo por hijo'
    return `Una copia por hijo del de ${stage.workspaceFrom ?? 'su dependencia'}`
  }
  if (stage.workspaceFrom) return `Continúa el de ${stage.workspaceFrom}`
  if (stage.workspace === 'INHERIT' && stage.dependsOn.length === 0) return 'Uno nuevo'
  return WORKSPACE[stage.workspace]
}

/**
 * Lo que dice el YAML, en listas: fases con sus dependencias, datos que se piden al lanzar y
 * agentes. El grafo llega en W7.
 */
export function DefinitionSummary({ definition }: { definition: WorkflowModel }) {
  return (
    <div className="definition-summary">
      <section aria-labelledby="stages-title">
        <h3 id="stages-title">Fases</h3>
        <ol className="definition-list stage-list">
          {definition.stages.map((s) => (
            <li key={s.id}>
              <code>{s.id}</code>
              {s.name && <span> {s.name}</span>} <span className="tag">{s.type}</span>
              <span className="muted small">
                {s.type === 'agent' && ` · Agente: ${s.agent ?? 'el del repositorio'}`}
                {s.type === 'command' && (
                  <>
                    {' · Ejecuta '}
                    <code>{s.command}</code>
                  </>
                )}
                {s.type === 'parallel' &&
                  ` · A la vez: ${s.children
                    .map(
                      (c) =>
                        (c.id === c.agent ? c.id : `${c.id} (${c.agent})`) +
                        (c.retry ? ` [${retryText(c.retry)}]` : ''),
                    )
                    .join(', ')}`}
                {s.retry && ` · Reintenta: ${retryText(s.retry)}`}
                {s.timeoutSeconds != null &&
                  ` · Tiempo máximo: ${formatSeconds(s.timeoutSeconds)} por intento`}
                {s.approval && (
                  <>
                    {' · Pide aprobar '}
                    <q>{s.approval.action}</q>
                    {` · ${RISK_LABEL[s.approval.risk]}`}
                    {s.approval.revise && ` · Corrige ${s.approval.revise}`}
                  </>
                )}
                {s.outputSchema && (
                  <>
                    {' · Salida: '}
                    <code>{Object.keys(s.outputSchema.properties).join(', ')}</code>
                  </>
                )}
                {s.outputSchemaPath && (
                  <>
                    {' · Salida: '}
                    <code>{s.outputSchemaPath}</code>
                  </>
                )}
                {s.checks.length > 0 && (
                  <>
                    {' · Comprueba '}
                    {s.checks.map((c, i) => (
                      <span key={`${c.name}.${i}`}>
                        {i > 0 && ', '}
                        <code>{checkText(c)}</code>
                      </span>
                    ))}
                  </>
                )}
                {s.condition && (
                  <>
                    {s.type === 'conditional' ? ' · Evalúa ' : ' · Solo si '}
                    <code>{s.condition}</code>
                  </>
                )}
                {s.dependsOn.length > 0 &&
                  ` · Depende de ${s.dependsOn
                    .map((d) => (d.optional ? `${d.stage} (opcional)` : d.stage))
                    .join(', ')}`}
                {WITH_WORKSPACE.includes(s.type) &&
                  ` · Worktree: ${workspaceLabel(s).toLowerCase()}`}
              </span>
            </li>
          ))}
        </ol>
      </section>
      {definition.inputs.length > 0 && (
        <section aria-labelledby="inputs-title">
          <h3 id="inputs-title">Datos al lanzar</h3>
          <ul className="definition-list">
            {definition.inputs.map((input) => (
              <li key={input.name}>
                <code>{input.name}</code> <span className="muted small">{input.type}</span>
                {input.required && <span className="tag">obligatorio</span>}
                {input.description && <span> · {input.description}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
      {definition.agents.length > 0 && (
        <section aria-labelledby="agents-title">
          <h3 id="agents-title">Agentes</h3>
          <ul className="definition-list">
            {definition.agents.map((agent) => (
              <li key={agent.name}>
                <code>{agent.name}</code>
                {agent.description && <span> · {agent.description}</span>}
                <span className="muted small">
                  {' '}
                  · Herramientas:{' '}
                  {agent.tools ? agent.tools.join(', ') || 'ninguna' : 'las del repositorio'}
                  {agent.provider && ` · ${providerName(agent.provider)}`}
                  {agent.model && ` · Modelo ${agent.model}`}
                  {agent.permissionMode && ` · ${agent.permissionMode}`}
                  {agent.maxTurns != null && ` · ${agent.maxTurns} turnos`}
                  {agent.maxBudgetUsd != null && ` · ${agent.maxBudgetUsd} US$`}
                  {agent.timeoutMinutes != null && ` · ${agent.timeoutMinutes} min`}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
