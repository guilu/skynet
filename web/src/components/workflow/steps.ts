import type {
  AgentDefinition,
  Phase,
  Problem,
  StageDefinition,
  VerificationCheck,
  WorkflowModel,
} from '../../api'
import { PHASES } from '../../api'
import { providerName } from '../../format'
import { RISK_LABEL } from '../approval/approvalFormat'
import { lanesOf } from '../graph/layout'
import { formatSeconds } from '../run/stageNotes'

/**
 * El «paso a paso» de una definición (WF-E): cada fase contada en palabras a partir de lo que ya
 * dice el YAML (tipo, agente, checks, reintentos, aprobación, juez, condición), sin claves nuevas.
 */

export type ChipTone = 'neutral' | 'warn' | 'bad'
export type ChipIcon =
  | 'after'
  | 'receives'
  | 'leaves'
  | 'returns'
  | 'check'
  | 'retry'
  | 'fix'
  | 'risk'
  | 'condition'
  | 'time'
  | 'judge'
  | 'people'
  | 'workspace'
  | 'auto'

export interface StepChip {
  icon: ChipIcon
  text: string
  tone?: ChipTone
}

/** Quién hace un paso: su agente y con qué. */
export interface StepWorker {
  agent: string | null
  provider: string
  model: string | null
  fallback: string | null
}

export interface Step {
  id: string
  n: number
  title: string
  /** El tipo en palabras: «Agente», «Tu aprobación», «Juez», «GitHub»… */
  kind: string
  /** Para el icono del paso. */
  icon: 'agent' | 'approval' | 'judge' | 'github' | 'command' | 'parallel' | 'other'
  workers: StepWorker[]
  phrase: string
  chips: StepChip[]
  prompt: string | null
  lane: Phase | null
}

export interface StepGroup {
  lane: Phase | null
  steps: Step[]
}

const KIND: Record<string, { kind: string; icon: Step['icon'] }> = {
  agent: { kind: 'Agente', icon: 'agent' },
  parallel: { kind: 'A la vez', icon: 'parallel' },
  'human-approval': { kind: 'Tu aprobación', icon: 'approval' },
  verification: { kind: 'Comprobación', icon: 'judge' },
  command: { kind: 'Comando', icon: 'command' },
  'github-pr': { kind: 'GitHub', icon: 'github' },
  'github-check': { kind: 'GitHub', icon: 'github' },
  'github-merge': { kind: 'GitHub', icon: 'github' },
}

const CHECK_TEXT: Record<VerificationCheck['name'], (c: VerificationCheck) => string> = {
  artifacts: (c) => `Deja ${c.paths.join(', ')}`,
  tests: () => 'Tests en verde',
  'tests-intact': () => 'No toca los tests',
  diff: () => 'Hay cambios (diff)',
  commit: () => 'Confirma un commit',
  scope: (c) => `Solo cambia ${c.paths.join(', ')}`,
  protected: () => 'Respeta las rutas protegidas',
  command: (c) => `Pasa ${c.command ?? 'el comando'}`,
  spec: () => 'Cumple la especificación',
}

const MERGE = { squash: 'con squash', merge: 'con un commit de fusión', rebase: 'con rebase' }

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
const list = (items: string[]) => items.join(', ').replace(/, ([^,]*)$/, ' y $1')

/** Las fases como las lee el grafo, para sus carriles. */
const lanesOfStages = (stages: StageDefinition[]) =>
  lanesOf(stages.map((s) => ({ id: s.id, dependsOn: s.dependsOn, children: [], phase: s.phase })))

/** Las salidas de otras fases que lee un prompt: `{{stages.spec.output.summary}}`. */
export function receivesFrom(prompt: string | null): { stage: string; fields: string[] }[] {
  const found = new Map<string, Set<string>>()
  for (const m of (prompt ?? '').matchAll(/\{\{\s*stages\.([\w-]+)\.output(?:\.([\w-]+))?/g)) {
    const fields = found.get(m[1]) ?? new Set<string>()
    if (m[2]) fields.add(m[2])
    found.set(m[1], fields)
  }
  return [...found].map(([stage, fields]) => ({ stage, fields: [...fields] }))
}

function worker(agents: Map<string, AgentDefinition>, name: string | null): StepWorker {
  const agent = name ? agents.get(name) : undefined
  if (!agent) return { agent: name, provider: 'repository', model: null, fallback: null }
  return {
    agent: agent.name,
    provider: agent.provider ?? 'claude-code',
    model: agent.model,
    fallback: agent.fallback?.provider ?? null,
  }
}

/** La frase de un paso: qué hace, en una o dos oraciones. */
function phrase(
  stage: StageDefinition,
  agents: Map<string, AgentDefinition>,
  title: (id: string | null) => string,
): string {
  switch (stage.type) {
    case 'agent':
      return (
        (stage.agent && agents.get(stage.agent)?.description) ??
        (stage.agent ? `Trabaja el agente ${stage.agent}.` : 'Trabaja el agente del repositorio.')
      )
    case 'parallel':
      return `${plural(stage.children.length, 'agente', 'agentes')} a la vez: ${list(
        stage.children.map((c) => c.id),
      )}.`
    case 'human-approval': {
      const revise = stage.approval?.revise
      return (
        'Se para hasta que alguien decide en Aprobaciones.' +
        (revise ? ` Si pides cambios, los hace «${title(revise)}».` : '')
      )
    }
    case 'verification': {
      const judge = stage.judge
      if (!judge) return 'Comprueba el resultado sin juez: solo los checks.'
      if (judge.guide)
        return `Ejecuta la guía de pruebas (${judge.guide.paths.join(', ')}) paso a paso.`
      const required = judge.rubric.filter((r) => r.required).length
      const parts = [
        judge.rubric.length > 0
          ? `Puntúa ${plural(judge.rubric.length, 'criterio', 'criterios')}${
              required > 0 ? ` (${plural(required, 'obligatorio', 'obligatorios')})` : ''
            }.`
          : judge.rubricFile
            ? `Puntúa la rúbrica de ${judge.rubricFile}.`
            : 'Puntúa con la rúbrica de siempre.',
      ]
      if (judge.refute > 0)
        parts.push(
          `${plural(judge.refute, 'escéptico intenta', 'escépticos intentan')} refutar cada hallazgo que bloquea.`,
        )
      if (judge.fix)
        parts.push(
          `Lo que queda lo corrige «${title(judge.fix)}», como mucho ${plural(judge.maxRounds, 'ronda', 'rondas')}.`,
        )
      return parts.join(' ')
    }
    case 'command':
      return `Ejecuta ${stage.command ?? 'un comando'} en el worktree.`
    case 'github-pr':
      return stage.pullRequest?.draft
        ? 'Sube la rama y abre la PR en borrador.'
        : 'Sube la rama y abre la PR.'
    case 'github-check': {
      const fix = stage.ci?.fix
      return (
        'Espera a que la CI termine.' +
        (fix
          ? ` Si falla, la corrige «${title(fix)}», como mucho ${plural(stage.ci!.maxRounds, 'ronda', 'rondas')}.`
          : '')
      )
    }
    case 'github-merge':
      return `Fusiona la PR ${MERGE[stage.merge?.method ?? 'squash']}.`
    default:
      return `Fase ${stage.type}.`
  }
}

function chips(stage: StageDefinition, title: (id: string | null) => string): StepChip[] {
  const out: StepChip[] = []
  if (stage.condition) out.push({ icon: 'condition', text: `Solo si ${stage.condition}` })
  if (stage.dependsOn.length > 0)
    out.push({
      icon: 'after',
      text: `Después de ${list(
        stage.dependsOn.map((d) => `«${title(d.stage)}»${d.optional ? ' (opcional)' : ''}`),
      )}`,
    })
  if (stage.workspaceFrom && stage.dependsOn.length > 1)
    out.push({ icon: 'workspace', text: `Continúa el worktree de «${title(stage.workspaceFrom)}»` })
  for (const r of receivesFrom(stage.prompt))
    out.push({
      icon: 'receives',
      text: `Recibe de «${title(r.stage)}»${r.fields.length > 0 ? `: ${r.fields.join(', ')}` : ''}`,
    })
  for (const check of stage.checks)
    out.push({
      icon: check.name === 'artifacts' ? 'leaves' : 'check',
      text: CHECK_TEXT[check.name](check),
    })
  const output = stage.outputSchema ? Object.keys(stage.outputSchema.properties) : []
  if (output.length > 0) out.push({ icon: 'returns', text: `Devuelve ${list(output)}` })
  if (stage.retry)
    out.push({
      icon: 'retry',
      text: `Si falla, lo intenta ${plural(stage.retry.maxAttempts, 'vez', 'veces')}`,
    })
  if (stage.approval) {
    const risk = stage.approval.risk
    out.push({
      icon: 'risk',
      text: RISK_LABEL[risk],
      tone: risk === 'HIGH' ? 'bad' : risk === 'MEDIUM' ? 'warn' : 'neutral',
    })
  }
  if (stage.judge) {
    if (stage.judge.rubric.length > 0)
      out.push({ icon: 'judge', text: plural(stage.judge.rubric.length, 'criterio', 'criterios') })
    if (stage.judge.refute > 0)
      out.push({ icon: 'people', text: plural(stage.judge.refute, 'escéptico', 'escépticos') })
  }
  if (stage.timeoutSeconds != null)
    out.push({ icon: 'time', text: `Como mucho ${formatSeconds(stage.timeoutSeconds)}` })
  return out
}

/** Los pasos de una definición, numerados en el orden del YAML y agrupados por etapa. */
export function stepsOf(definition: WorkflowModel): StepGroup[] {
  const agents = new Map(definition.agents.map((a) => [a.name, a]))
  const byId = new Map(definition.stages.map((s) => [s.id, s]))
  const title = (id: string | null) => (id ? (byId.get(id)?.name ?? id) : '')
  const lanes = lanesOfStages(definition.stages)
  const groups: StepGroup[] = []
  definition.stages.forEach((stage, i) => {
    const info = KIND[stage.type] ?? { kind: stage.type, icon: 'other' as const }
    const judged = stage.type === 'verification' && stage.judge != null
    const workers =
      stage.type === 'agent'
        ? [worker(agents, stage.agent)]
        : stage.type === 'parallel'
          ? stage.children.map((c) => worker(agents, c.agent))
          : judged
            ? stage.judge!.agents.map((j) => worker(agents, j.agent))
            : []
    const step: Step = {
      id: stage.id,
      n: i + 1,
      title: stage.name ?? stage.id,
      kind: judged ? 'Juez' : info.kind,
      icon: info.icon,
      workers,
      phrase: phrase(stage, agents, title),
      chips: chips(stage, title),
      prompt:
        stage.prompt ??
        (stage.type === 'agent' && stage.agent ? (agents.get(stage.agent)?.prompt ?? null) : null),
      lane: lanes.get(stage.id) ?? null,
    }
    const last = groups.at(-1)
    if (last && last.lane === step.lane) last.steps.push(step)
    else groups.push({ lane: step.lane, steps: [step] })
  })
  return groups
}

/** Las cifras de arriba: fases y etapas, agentes, aprobaciones, jueces y si entrega por PR. */
export function figuresOf(definition: WorkflowModel) {
  const stages = definition.stages
  const lanes = new Set(lanesOfStages(stages).values())
  const judges = new Set(stages.flatMap((s) => s.judge?.agents.map((a) => a.agent) ?? []))
  return {
    stages: stages.length,
    lanes: PHASES.filter((p) => lanes.has(p)).length,
    agents: definition.agents.length,
    gates: stages.filter((s) => s.type === 'human-approval').length,
    judges: judges.size,
    delivers: stages.some((s) => s.type === 'github-pr'),
    merges: stages.some((s) => s.type === 'github-merge'),
  }
}

/** Los permisos de un agente, en palabras. */
export function permissionText(agent: AgentDefinition): string {
  switch (agent.permissionMode) {
    case 'plan':
      return 'Solo lectura'
    case 'dontAsk':
      return 'Solo lo que tiene permitido'
    case 'acceptEdits':
      return 'Puede editar ficheros'
    default:
      return 'Lo que permita el repositorio'
  }
}

/** Las fases en las que trabaja o juzga cada agente. */
export function stagesOfAgent(definition: WorkflowModel, agent: string): string[] {
  return definition.stages
    .filter(
      (s) =>
        s.agent === agent ||
        s.children.some((c) => c.agent === agent) ||
        s.judge?.agents.some((j) => j.agent === agent) ||
        s.judge?.guide?.agent === agent,
    )
    .map((s) => s.id)
}

export interface ReadyItem {
  tone: 'ok' | 'warn' | 'bad'
  text: string
}

/** Qué necesita para lanzarse: errores, comando de verificación, cuota de los proveedores y GitHub. */
export function readinessOf(definition: WorkflowModel | null, problems: Problem[]): ReadyItem[] {
  const errors = problems.filter((p) => p.severity === 'ERROR').length
  const items: ReadyItem[] = [
    errors > 0
      ? {
          tone: 'bad',
          text: `Tiene ${plural(errors, 'error', 'errores')}: corrígelos para lanzarlo.`,
        }
      : { tone: 'ok', text: 'La definición no tiene errores.' },
  ]
  if (!definition) return items
  const testing = definition.stages
    .filter((s) => s.checks.some((c) => c.name === 'tests'))
    .map((s) => `«${s.name ?? s.id}»`)
  if (testing.length > 0)
    items.push({
      tone: 'warn',
      text: `El repositorio necesita su comando de verificación: lo usan los tests de ${list(testing)}.`,
    })
  const providers = [
    ...new Set(
      definition.stages.flatMap((s) => {
        const names = [
          s.agent,
          ...s.children.map((c) => c.agent),
          ...(s.judge?.agents.map((j) => j.agent) ?? []),
        ]
        return names
          .filter((n): n is string => n != null)
          .map((n) => definition.agents.find((a) => a.name === n)?.provider ?? 'claude-code')
      }),
    ),
  ]
  if (providers.length > 0)
    items.push({ tone: 'warn', text: `Necesita cuota de ${list(providers.map(providerName))}.` })
  const github = definition.stages.filter((s) => s.type.startsWith('github-')).length
  if (github > 0)
    items.push({
      tone: 'warn',
      text: `${github === 1 ? 'La fase de GitHub solo corre' : `Las ${github} fases de GitHub solo corren`} si el repositorio está en GitHub con la App.`,
    })
  return items
}
