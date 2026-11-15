import * as Menu from '@radix-ui/react-dropdown-menu'
import {
  ArrowDown,
  ArrowUp,
  Bot,
  CircleAlert,
  Code,
  Ellipsis,
  FileText,
  GitPullRequest,
  Hand,
  Plus,
  Scale,
  Terminal,
  TextCursorInput,
  Trash2,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { useState, type KeyboardEvent } from 'react'
import type { Problem, WorkflowModel } from '../../api'
import { cn } from '../../lib/cn'
import { problemBadge, problemsByStage } from '../graph/definitionProblems'
import { PHASE_LABEL } from '../graph/layout'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'
import {
  addStep,
  dependentsOf,
  EditError,
  moveStage,
  readAgentNames,
  readInputs,
  readStage,
  removeStage,
  type StepKind,
} from './definitionEdits'
import { AgentsPanel, InputsPanel, WorkflowPanel } from './DefinitionPanels'
import { StepCard } from './DefinitionOverview'
import { applyQuickFix, quickFixOf } from './quickFix'
import { StagePanel, type ApplyEdit } from './StagePanel'
import { STEP_ICON } from './stepIcons'
import { stepsOf, type Step } from './steps'

/** «Añadir paso»: cada tipo, con lo que hace en una frase. */
const PALETTE: { kind: StepKind; label: string; text: string; icon: LucideIcon }[] = [
  { kind: 'agent', label: 'Agente', text: 'Un agente hace algo en el worktree', icon: Bot },
  {
    kind: 'judge',
    label: 'Juez',
    text: 'Uno o varios agentes puntúan el cambio con criterios',
    icon: Scale,
  },
  {
    kind: 'human-approval',
    label: 'Tu aprobación',
    text: 'Se para hasta que alguien decide',
    icon: Hand,
  },
  {
    kind: 'parallel',
    label: 'Varios a la vez',
    text: 'Agentes en paralelo, cada uno en su copia',
    icon: Users,
  },
  { kind: 'command', label: 'Comando', text: 'Un comando de shell en el worktree', icon: Terminal },
  {
    kind: 'delivery',
    label: 'Entrega por PR',
    text: 'Abre la PR, espera la CI, apruebas y fusiona (4 pasos)',
    icon: GitPullRequest,
  },
]

/** Lo que no es un paso y también se edita en el panel (W8-C). */
type Panel = 'workflow' | 'agents' | 'inputs'

interface Props {
  /** El YAML tal como está ahora en el editor. */
  text: string
  onChange: (text: string) => void
  /** Lo último que se pudo leer y validar, para la lista. */
  definition: WorkflowModel
  problems: Problem[]
  /** El texto con el que se validó `definition`. */
  source: string
  /** Salta a esa posición en la pestaña YAML. */
  reveal: (line: number, column: number) => void
  /** La línea de una fase (o de su campo) en el texto, para «Ver en YAML». */
  lineOf: (id: string, field?: string) => { line: number; column: number } | null
  /** Si se puede cambiar el id del workflow: solo en el alta. */
  keyEditable?: boolean
  /** Para Ctrl/Cmd+Z: los botones están en la barra del editor. */
  undo?: () => void
  redo?: () => void
}

function AddStep({ after, onAdd }: { after: string | null; onAdd: (kind: StepKind) => void }) {
  return (
    <Menu.Root modal={false}>
      <Menu.Trigger className="wf-add-step">
        <Plus size={14} strokeWidth={2.5} aria-hidden="true" />
        Añadir paso
        {after && <span className="visually-hidden"> detrás de {after}</span>}
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu wf-palette" align="start" sideOffset={6}>
          <Menu.Label className="wf-palette-label">
            {after ? `Añadir detrás de «${after}»` : 'Añadir al final'}
          </Menu.Label>
          {PALETTE.map(({ kind, label, text, icon: Icon }) => (
            <Menu.Item
              key={kind}
              className="menu-item wf-palette-item"
              onSelect={() => onAdd(kind)}
            >
              <span className={cn('wf-step-icon', `wf-palette-${kind}`)} aria-hidden="true">
                <Icon size={18} strokeWidth={2.25} />
              </span>
              <span>
                <strong>{label}</strong>
                <span className="muted small">{text}</span>
              </span>
            </Menu.Item>
          ))}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}

/**
 * El editor por pasos de un borrador (WF-F): el «paso a paso» de WF-E, editable. Se añade un paso
 * entre dos con una paleta que explica cada tipo, se sube o baja (Alt+↑/↓ o su menú) cuando eso es
 * solo cambiar el orden, y se cambia en el panel de la derecha. Los problemas salen en su paso y,
 * los que el control plane sabe arreglar («¿quisiste decir…?»), se arreglan en un clic. Cada
 * cambio es una edición del YAML, que se deshace igual que escribir en él.
 */
export function StepsEditor({
  text,
  onChange,
  definition,
  problems,
  source,
  reveal,
  lineOf,
  keyEditable = false,
  undo,
  redo,
}: Props) {
  const [selected, setSelectedState] = useState<string | null>(null)
  const [panel, setPanelState] = useState<Panel | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const select = (id: string | null) => {
    setSelectedState(id)
    if (id) setPanelState(null)
  }
  const setPanel = (next: Panel | null) => {
    setPanelState(next)
    if (next) setSelectedState(null)
  }

  const apply: ApplyEdit = (edit) => {
    try {
      const next = edit(text)
      setMessage(null)
      if (next !== text) onChange(next)
      return true
    } catch (error) {
      if (!(error instanceof EditError)) throw error
      setMessage(error.message)
      return false
    }
  }

  const add = (kind: StepKind, after: string | null) => {
    try {
      const result = addStep(text, kind, after)
      setMessage(null)
      onChange(result.text)
      select(result.id)
    } catch (error) {
      if (!(error instanceof EditError)) throw error
      setMessage(error.message)
    }
  }
  const move = (id: string, direction: 'up' | 'down') => {
    apply((t) => moveStage(t, id, direction))
  }
  const editYaml = (id: string, field?: string) => {
    const at = lineOf(id, field)
    if (at) reveal(at.line, at.column)
  }

  const groups = stepsOf(definition)
  const steps = groups.flatMap((g) => g.steps)
  const byStage = problemsByStage(source, problems)
  const selectedStage = selected && readStage(text, selected) ? selected : null
  const errors = problems.filter((p) => p.severity === 'ERROR')
  // El primer error, con su paso si lo tiene: el aviso de arriba lo explica y lo arregla.
  const first = errors[0]
  const firstStage = first
    ? ([...byStage].find(([, list]) => list.includes(first))?.[0] ?? null)
    : null
  const firstStep = steps.find((s) => s.id === firstStage)
  const fix = first ? quickFixOf(first) : null

  // Alt+↑/↓ mueven el paso elegido (fuera de los campos del panel).
  const onKeyDown = (e: KeyboardEvent) => {
    const field = (e.target as HTMLElement).closest('input, textarea, select')
    // Ctrl/Cmd+Z y Ctrl/Cmd+Mayús+Z deshacen y rehacen fuera de los campos del panel.
    if (!field && (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
      e.preventDefault()
      if (e.shiftKey) redo?.()
      else undo?.()
      return
    }
    if (!e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return
    const target = e.target as HTMLElement
    if (target.closest('input, textarea, select, .graph-editor-panel')) return
    const id = target.closest<HTMLElement>('[data-step]')?.dataset.step ?? selectedStage
    if (!id) return
    e.preventDefault()
    select(id)
    move(id, e.key === 'ArrowUp' ? 'up' : 'down')
  }

  const agents = readAgentNames(text).length
  const inputs = readInputs(text).length

  const stepMenu = (step: Step) => (
    <Menu.Root modal={false}>
      <Menu.Trigger className="icon-btn icon-btn-sm" aria-label={`Más acciones: ${step.title}`}>
        <Ellipsis size={18} strokeWidth={2.5} aria-hidden="true" />
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu" align="end" sideOffset={8}>
          <Menu.Item className="menu-item" onSelect={() => move(step.id, 'up')}>
            <ArrowUp size={18} strokeWidth={2.25} aria-hidden="true" />
            Subir <span className="muted small">Alt+↑</span>
          </Menu.Item>
          <Menu.Item className="menu-item" onSelect={() => move(step.id, 'down')}>
            <ArrowDown size={18} strokeWidth={2.25} aria-hidden="true" />
            Bajar <span className="muted small">Alt+↓</span>
          </Menu.Item>
          <Menu.Item className="menu-item" onSelect={() => editYaml(step.id)}>
            <Code size={18} strokeWidth={2.25} aria-hidden="true" />
            Ver en YAML
          </Menu.Item>
          <Menu.Item className="menu-item menu-danger" onSelect={() => setDeleting(step.id)}>
            <Trash2 size={18} strokeWidth={2.25} aria-hidden="true" />
            Borrar paso…
          </Menu.Item>
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )

  return (
    <div className="steps-editor" onKeyDown={onKeyDown}>
      <div className="steps-toolbar">
        <div className="wf-filters" role="group" aria-label="Más del workflow">
          {(
            [
              ['workflow', 'Datos del workflow', FileText],
              ['agents', `Agentes ${agents}`, Bot],
              ['inputs', `Datos al lanzar ${inputs}`, TextCursorInput],
            ] as const
          ).map(([id, label, Icon]) => (
            <button
              key={id}
              type="button"
              className="chip"
              aria-pressed={panel === id}
              onClick={() => setPanel(panel === id ? null : id)}
            >
              <Icon size={16} strokeWidth={2.25} aria-hidden="true" />
              {label}
            </button>
          ))}
        </div>
        <span className="muted small">Mover: Alt+↑/↓</span>
        <p className="graph-message" role="status">
          {message}
        </p>
      </div>

      {first && (
        <section className="steps-alert" aria-labelledby="steps-alert-title">
          <CircleAlert size={20} strokeWidth={2.25} aria-hidden="true" />
          <div>
            <p id="steps-alert-title">
              <strong>
                No se puede publicar: {errors.length === 1 ? '1 error' : `${errors.length} errores`}
              </strong>
            </p>
            <p>{first.message}</p>
            <p className="muted small">
              {[
                firstStep && `Paso ${firstStep.n} · ${firstStep.title}`,
                first.line != null && `línea ${first.line}`,
              ]
                .filter(Boolean)
                .join(' · ')}
            </p>
            <div className="steps-alert-actions">
              {fix && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    const next = applyQuickFix(text, first)
                    if (next) onChange(next)
                    else setMessage('El texto ha cambiado: arréglalo en el YAML')
                  }}
                >
                  Cambiar a «{fix.to}»
                </Button>
              )}
              {firstStep && (
                <Button variant="link" size="sm" onClick={() => select(firstStep.id)}>
                  Ir al paso
                </Button>
              )}
              {first.line != null && (
                <Button
                  variant="link"
                  size="sm"
                  onClick={() => reveal(first.line ?? 1, first.column ?? 1)}
                >
                  Ver en YAML
                </Button>
              )}
            </div>
          </div>
        </section>
      )}

      <div className="graph-editor-body">
        <section className="wf-steps" aria-label="Pasos">
          {steps.length === 0 && <AddStep after={null} onAdd={(kind) => add(kind, null)} />}
          {groups.map((g) => (
            <div key={`${g.lane}.${g.steps[0].id}`} className="wf-step-group">
              {g.lane && <h4 className="wf-step-lane">{PHASE_LABEL[g.lane]}</h4>}
              <ol className="wf-step-list" start={g.steps[0].n}>
                {g.steps.map((step) => {
                  const Icon = STEP_ICON[step.icon]
                  const badge = problemBadge(byStage.get(step.id) ?? [])
                  return (
                    <li key={step.id} className={cn('wf-step', `wf-step-${step.icon}`)}>
                      <span className="wf-step-icon" aria-hidden="true">
                        <Icon size={18} strokeWidth={2.25} />
                      </span>
                      <div className="wf-step-slot">
                        <StepCard
                          step={step}
                          onSelect={() => select(step.id)}
                          selected={step.id === selectedStage}
                          badge={
                            badge && (
                              <span
                                className={cn(
                                  'wf-step-badge',
                                  badge.severity === 'ERROR' ? 'is-error' : 'is-warning',
                                )}
                              >
                                <CircleAlert size={14} strokeWidth={2.5} aria-hidden="true" />
                                {badge.label}
                              </span>
                            )
                          }
                          menu={stepMenu(step)}
                        />
                        <AddStep after={step.id} onAdd={(kind) => add(kind, step.id)} />
                      </div>
                    </li>
                  )
                })}
              </ol>
            </div>
          ))}
        </section>

        <div className="graph-editor-panel">
          {panel === 'workflow' ? (
            <WorkflowPanel
              text={text}
              problems={problems}
              apply={apply}
              keyEditable={keyEditable}
            />
          ) : panel === 'agents' ? (
            <AgentsPanel text={text} problems={problems} apply={apply} />
          ) : panel === 'inputs' ? (
            <InputsPanel text={text} problems={problems} apply={apply} />
          ) : selectedStage ? (
            <StagePanel
              key={selectedStage}
              text={text}
              id={selectedStage}
              problems={problems}
              apply={apply}
              onRenamed={(id) => select(id)}
              onDelete={() => setDeleting(selectedStage)}
              onEditYaml={(field) => editYaml(selectedStage, field)}
              onRevealProblem={reveal}
            />
          ) : (
            <section className="card stage-panel" aria-labelledby="steps-help-title">
              <h2 id="steps-help-title">Propiedades</h2>
              <p className="muted small">
                Elige un paso para cambiarlo, o añade uno con «Añadir paso». El nombre, los agentes
                y los datos al lanzar están en sus botones, encima de los pasos.
              </p>
            </section>
          )}
        </div>
      </div>

      <Sheet
        open={deleting != null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`Borrar el paso ${deleting ?? ''}`}
        description="Se quita del YAML; hasta guardar, se puede deshacer."
      >
        {deleting && (
          <div className="form">
            {dependentsOf(text, deleting).length > 0 ? (
              <p>
                Dependen de él {dependentsOf(text, deleting).join(', ')}: se les quita esa
                dependencia (y su <code>workspaceFrom</code>, si lo continuaba).
              </p>
            ) : (
              <p>Ningún paso depende de él.</p>
            )}
            <div className="form-actions">
              <Button
                variant="danger"
                onClick={() => {
                  if (apply((t) => removeStage(t, deleting))) select(null)
                  setDeleting(null)
                }}
              >
                <Trash2 size={18} aria-hidden="true" />
                Borrar paso
              </Button>
              <Button variant="secondary" onClick={() => setDeleting(null)}>
                Cancelar
              </Button>
            </div>
          </div>
        )}
      </Sheet>
    </div>
  )
}
