import {
  ArrowRightFromLine,
  ArrowRightToLine,
  Bot,
  Check,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  CircleX,
  CornerDownRight,
  FileText,
  FolderGit2,
  GitBranch,
  GitPullRequest,
  Hand,
  Layers,
  Lock,
  Pencil,
  Repeat,
  Scale,
  Sparkles,
  Timer,
  TriangleAlert,
  Users,
  Wrench,
  type LucideIcon,
} from 'lucide-react'
import { useState, type ReactNode } from 'react'
import type { WorkflowModel } from '../../api'
import { providerName } from '../../format'
import { cn } from '../../lib/cn'
import { useMediaQuery } from '../../useMediaQuery'
import { STEP_ICON, stepWho } from './stepIcons'
import { PHASE_LABEL } from '../graph/layout'
import { Sheet } from '../ui/Sheet'
import { providerInfo } from './overview'
import {
  figuresOf,
  permissionText,
  readinessOf,
  stagesOfAgent,
  stepsOf,
  type ChipIcon,
  type ReadyItem,
  type Step,
  type StepWorker,
} from './steps'
import type { Problem } from '../../api'

const CHIP_ICON: Record<ChipIcon, LucideIcon> = {
  after: CornerDownRight,
  receives: ArrowRightToLine,
  leaves: FileText,
  returns: ArrowRightFromLine,
  check: Check,
  retry: Repeat,
  fix: Wrench,
  risk: TriangleAlert,
  condition: GitBranch,
  time: Timer,
  judge: Scale,
  people: Users,
  workspace: FolderGit2,
  auto: Sparkles,
}

const READY_ICON = { ok: CircleCheck, warn: CircleAlert, bad: CircleX }

function Mono({ provider }: { provider: string }) {
  return (
    <span className={cn('wf-mono', `wf-mono-${provider}`)} aria-hidden="true">
      {providerInfo(provider).mono}
    </span>
  )
}

/** «Claude Code · arquitecto · opus · reserva: Codex» */
function Worker({ worker: w }: { worker: StepWorker }) {
  return (
    <span className="wf-step-worker">
      <Mono provider={w.provider} />
      <strong>{providerInfo(w.provider).name}</strong>
      {w.agent && <span className="muted"> · {w.agent}</span>}
      {w.model && <span className="muted"> · {w.model}</span>}
      {w.fallback && <span className="muted"> · reserva: {providerName(w.fallback)}</span>}
    </span>
  )
}

function StepChips({ step }: { step: Step }) {
  if (step.chips.length === 0) return null
  return (
    <ul className="wf-step-chips" aria-label="Detalles">
      {step.chips.map((c) => {
        const Icon = CHIP_ICON[c.icon]
        return (
          <li
            key={c.text}
            className={cn(c.tone && c.tone !== 'neutral' && `wf-step-chip-${c.tone}`)}
          >
            <Icon size={14} strokeWidth={2.25} aria-hidden="true" />
            {c.text}
          </li>
        )
      })}
    </ul>
  )
}

function StepHead({
  step,
  onSelect,
  badge,
  menu,
}: {
  step: Step
  onSelect?: () => void
  badge?: ReactNode
  menu?: ReactNode
}) {
  return (
    <div className="wf-step-head">
      <h4 className="wf-step-title">
        {onSelect ? (
          <button type="button" className="wf-step-select" data-step={step.id} onClick={onSelect}>
            {step.n}. {step.title}
          </button>
        ) : (
          <>
            {step.n}. {step.title}
          </>
        )}
      </h4>
      {badge}
      <span className="tag">{step.kind}</span>
      <code className="muted small">{step.id}</code>
      {menu && <span className="wf-step-menu">{menu}</span>}
    </div>
  )
}

/**
 * Un paso entero: título, quién, la frase, los chips y el prompt plegado. En el editor (WF-F), el
 * título es el botón que lo elige y lleva sus problemas y su menú.
 */
export function StepCard({
  step,
  onSelect,
  selected = false,
  badge,
  menu,
}: {
  step: Step
  onSelect?: () => void
  selected?: boolean
  badge?: ReactNode
  menu?: ReactNode
}) {
  return (
    <div
      className={cn('wf-step-card', onSelect && 'wf-step-card-edit', selected && 'is-selected')}
      onClick={(e) => {
        // Pinchar en la tarjeta la elige, salvo en sus botones, enlaces y el prompt plegado.
        if (onSelect && !(e.target as HTMLElement).closest('button, a, details, [role="menu"]'))
          onSelect()
      }}
    >
      <StepHead step={step} onSelect={onSelect} badge={badge} menu={menu} />
      {step.workers.map((w, i) => (
        <Worker key={`${w.agent}.${i}`} worker={w} />
      ))}
      <p className="wf-step-phrase">{step.phrase}</p>
      <StepChips step={step} />
      {step.prompt && (
        <details className="wf-step-prompt">
          <summary>Ver prompt</summary>
          <pre>{step.prompt}</pre>
        </details>
      )}
    </div>
  )
}

/**
 * El «Resumen» de una definición (WF-E): cifras y el paso a paso por etapa. En el móvil, cada paso
 * es una fila que abre una hoja inferior con todo lo suyo.
 */
export function DefinitionOverview({ definition }: { definition: WorkflowModel }) {
  const narrow = useMediaQuery('(max-width: 720px)')
  const [open, setOpen] = useState<Step | null>(null)
  const groups = stepsOf(definition)
  const f = figuresOf(definition)
  const figures = [
    {
      icon: Layers,
      value: f.stages,
      label: f.lanes > 0 ? `fases en ${f.lanes} etapas` : f.stages === 1 ? 'fase' : 'fases',
    },
    { icon: Bot, value: f.agents, label: f.agents === 1 ? 'agente' : 'agentes' },
    { icon: Hand, value: f.gates, label: 'te piden aprobar' },
    { icon: Scale, value: f.judges, label: f.judges === 1 ? 'juez' : 'jueces' },
    ...(f.delivers
      ? [
          {
            icon: GitPullRequest,
            value: 'PR',
            label: f.merges ? 'abre, espera la CI y fusiona' : 'abre la PR',
          },
        ]
      : []),
  ]
  return (
    <div className="overview">
      <ul className="overview-figures" aria-label="En cifras">
        {figures.map(({ icon: Icon, value, label }) => (
          <li key={label}>
            <Icon size={20} strokeWidth={2.25} aria-hidden="true" />
            <span>
              <strong>{value}</strong> {label}
            </span>
          </li>
        ))}
      </ul>
      <section className="wf-steps" aria-labelledby="steps-title">
        <h3 id="steps-title">
          Paso a paso <span className="muted small">en el orden del YAML</span>
        </h3>
        {groups.map((g) => (
          <div key={`${g.lane}.${g.steps[0].id}`} className="wf-step-group">
            {g.lane && <h4 className="wf-step-lane">{PHASE_LABEL[g.lane]}</h4>}
            <ol className="wf-step-list" start={g.steps[0].n}>
              {g.steps.map((step) => {
                const Icon = STEP_ICON[step.icon]
                return (
                  <li key={step.id} className={cn('wf-step', `wf-step-${step.icon}`)}>
                    <span className="wf-step-icon" aria-hidden="true">
                      <Icon size={18} strokeWidth={2.25} />
                    </span>
                    {narrow ? (
                      <button type="button" className="wf-step-row" onClick={() => setOpen(step)}>
                        <span>
                          <span className="wf-step-title">
                            {step.n}. {step.title}
                          </span>
                          <span className="muted small">{stepWho(step)}</span>
                        </span>
                        <ChevronRight size={20} aria-hidden="true" />
                      </button>
                    ) : (
                      <StepCard step={step} />
                    )}
                  </li>
                )
              })}
            </ol>
          </div>
        ))}
      </section>
      <Sheet
        open={open != null}
        onOpenChange={(next) => !next && setOpen(null)}
        title={open ? `${open.n}. ${open.title}` : ''}
      >
        {open && <StepCard step={open} />}
      </Sheet>
    </div>
  )
}

/** «Listo para lanzar» (WF-E): lo que necesita y los problemas, en lugar de la tarjeta «Problemas». */
export function ReadyItems({
  definition,
  problems,
}: {
  definition: WorkflowModel | null
  problems: Problem[]
}) {
  const items: ReadyItem[] = readinessOf(definition, problems)
  return (
    <ul className="ready-items">
      {items.map((item) => {
        const Icon = READY_ICON[item.tone]
        return (
          <li key={item.text} className={`ready-${item.tone}`}>
            <Icon size={16} strokeWidth={2.5} aria-hidden="true" />
            {item.text}
          </li>
        )
      })}
    </ul>
  )
}

/** «Al lanzar te pide»: los datos de entrada, o que solo pide el trabajo. */
export function LaunchInputs({ definition }: { definition: WorkflowModel }) {
  if (definition.inputs.length === 0)
    return (
      <p className="muted small">
        Solo el trabajo: clave, título y descripción. No tiene datos de entrada propios.
      </p>
    )
  return (
    <ul className="definition-list">
      {definition.inputs.map((input) => (
        <li key={input.name}>
          <code>{input.name}</code>{' '}
          <span className="muted small">
            {input.required ? 'obligatorio' : 'opcional'} · {input.type}
          </span>
          {input.description && <span> · {input.description}</span>}
        </li>
      ))}
    </ul>
  )
}

/** Los agentes: proveedor, modelo, permisos en palabras y en qué fases trabajan. */
export function AgentList({ definition }: { definition: WorkflowModel }) {
  if (definition.agents.length === 0)
    return <p className="muted small">Usa el agente del repositorio en todas sus fases.</p>
  return (
    <ul className="agent-list">
      {definition.agents.map((agent) => {
        const provider = agent.provider ?? 'claude-code'
        const readOnly = agent.permissionMode === 'plan'
        return (
          <li key={agent.name}>
            <Mono provider={provider} />
            <div>
              <p>
                <strong>{agent.name}</strong>
                <span className="muted small">
                  {' '}
                  · {providerName(provider)}
                  {agent.model && ` · ${agent.model}`}
                </span>
              </p>
              <p className="muted small">
                {readOnly ? (
                  <Lock size={13} aria-hidden="true" />
                ) : (
                  <Pencil size={13} aria-hidden="true" />
                )}{' '}
                {permissionText(agent)}
                {agent.fallback && ` · reserva ${providerName(agent.fallback.provider)}`}
              </p>
              <p className="agent-stages">
                {stagesOfAgent(definition, agent.name).map((id) => (
                  <code key={id}>{id}</code>
                ))}
              </p>
            </div>
          </li>
        )
      })}
    </ul>
  )
}
