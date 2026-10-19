import { Bot, FileText, Redo2, TextCursorInput, Trash2, Undo2 } from 'lucide-react'
import { lazy, Suspense, useState, type KeyboardEvent } from 'react'
import type { Problem, WorkflowModel } from '../../api'
import type { GraphSelection } from '../graph/DefinitionGraph'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'
import { LinesSkeleton } from '../ui/Skeleton'
import {
  addDependency,
  addStage,
  dependentsOf,
  EditError,
  readStage,
  readStages,
  removeDependency,
  removeStage,
  setDependencyOptional,
  STAGE_TYPES,
  type StageType,
} from './definitionEdits'
import { AgentsPanel, InputsPanel, WorkflowPanel } from './DefinitionPanels'
import { StagePanel, type ApplyEdit } from './StagePanel'

const DefinitionGraph = lazy(() => import('../graph/DefinitionGraph'))

const TYPE_LABEL: Record<StageType, string> = {
  agent: 'Agente (agent)',
  command: 'Comando (command)',
  parallel: 'En paralelo (parallel)',
  conditional: 'Condicional (conditional)',
  verification: 'Verificación (verification)',
  'human-approval': 'Aprobación (human-approval)',
}

interface Props {
  /** El YAML tal como está ahora en el editor. */
  text: string
  onChange: (text: string) => void
  /** Lo último que se pudo leer y validar, para el dibujo. */
  definition: WorkflowModel
  problems: Problem[]
  /** El texto con el que se validó `definition`. */
  source: string
  /** Salta a esa posición en la pestaña YAML. */
  reveal: (line: number, column: number) => void
  /** La línea de una fase (o de su campo) en el texto, para «Editar en YAML». */
  lineOf: (id: string, field?: string) => { line: number; column: number } | null
  undo?: () => void
  redo?: () => void
  /** Si se puede cambiar el id del workflow: solo en el alta. */
  keyEditable?: boolean
}

/** Lo que no es una fase ni una flecha y también se edita en el panel (W8-C). */
const PANELS = [
  { id: 'workflow', label: 'Workflow', icon: FileText },
  { id: 'agents', label: 'Agentes', icon: Bot },
  { id: 'inputs', label: 'Entradas', icon: TextCursorInput },
] as const
type Panel = (typeof PANELS)[number]['id']

/**
 * El grafo de un borrador, editable (W8-B): añadir fases desde la paleta, unirlas arrastrando,
 * elegir una flecha para hacerla opcional o quitarla, y cambiar una fase en su panel. Cada cambio
 * es una edición del YAML (se deshace igual que escribir en él). Todo lo que se hace con el ratón
 * se puede hacer desde la paleta y el panel, con el teclado. El panel también edita el workflow,
 * sus agentes y sus entradas (W8-C).
 */
export function GraphEditor({
  text,
  onChange,
  definition,
  problems,
  source,
  reveal,
  lineOf,
  undo,
  redo,
  keyEditable = false,
}: Props) {
  const [selection, setSelectionState] = useState<GraphSelection>(null)
  const [panel, setPanelState] = useState<Panel | null>(null)
  const setSelection = (next: GraphSelection) => {
    setSelectionState(next)
    if (next) setPanelState(null)
  }
  const setPanel = (next: Panel | null) => {
    setPanelState(next)
    if (next) setSelectionState(null)
  }
  const [message, setMessage] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)

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

  const selectedStage =
    selection && 'stage' in selection && readStage(text, selection.stage) ? selection.stage : null
  const selectedEdge =
    selection &&
    'from' in selection &&
    readStages(text).some(
      (s) => s.id === selection.to && s.dependsOn.some((d) => d.stage === selection.from),
    )
      ? selection
      : null
  // Del texto, no de la validación: el cambio se ve en cuanto se hace.
  const edgeOptional =
    selectedEdge &&
    readStages(text)
      .find((s) => s.id === selectedEdge.to)
      ?.dependsOn.find((d) => d.stage === selectedEdge.from)?.optional

  const add = (type: StageType) => {
    try {
      const result = addStage(text, type, { after: selectedStage })
      setMessage(null)
      onChange(result.text)
      setSelection({ stage: result.id })
    } catch (error) {
      if (!(error instanceof EditError)) throw error
      setMessage(error.message)
    }
  }

  // Ctrl/Cmd+Z y Ctrl/Cmd+Mayús+Z deshacen y rehacen fuera de los campos del panel.
  const onKeyDown = (e: KeyboardEvent) => {
    const target = e.target as HTMLElement
    if (target.closest('input, textarea, select')) return
    if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'z') return
    e.preventDefault()
    if (e.shiftKey) redo?.()
    else undo?.()
  }

  const editYaml = (id: string, field?: string) => {
    const at = lineOf(id, field)
    if (at) reveal(at.line, at.column)
  }

  return (
    <div className="graph-editor" onKeyDown={onKeyDown}>
      <div className="graph-toolbar">
        <label className="graph-palette">
          <span>Añadir fase</span>
          <select
            value=""
            onChange={(e) => {
              if (e.target.value) add(e.target.value as StageType)
            }}
          >
            <option value="">
              {selectedStage ? `Detrás de ${selectedStage}…` : 'Elige el tipo…'}
            </option>
            {STAGE_TYPES.map((type) => (
              <option key={type} value={type}>
                {TYPE_LABEL[type]}
              </option>
            ))}
          </select>
        </label>
        {undo && (
          <Button variant="secondary" size="sm" onClick={undo}>
            <Undo2 size={16} aria-hidden="true" />
            Deshacer
          </Button>
        )}
        {redo && (
          <Button variant="secondary" size="sm" onClick={redo}>
            <Redo2 size={16} aria-hidden="true" />
            Rehacer
          </Button>
        )}
        <div className="graph-panels" role="group" aria-label="Más del workflow">
          {PANELS.map(({ id, label, icon: Icon }) => (
            <Button
              key={id}
              variant="secondary"
              size="sm"
              aria-pressed={panel === id}
              onClick={() => setPanel(panel === id ? null : id)}
            >
              <Icon size={16} aria-hidden="true" />
              {label}
            </Button>
          ))}
        </div>
        <p className="graph-message" role="status">
          {message}
        </p>
      </div>
      <div className="graph-editor-body">
        <div className="graph-editor-canvas">
          <Suspense fallback={<LinesSkeleton label="el grafo" lines={6} />}>
            <DefinitionGraph
              definition={definition}
              problems={problems}
              source={source}
              selection={selection}
              onSelect={setSelection}
              onConnect={(from, to) => apply((t) => addDependency(t, to, from))}
            />
          </Suspense>
          <p className="hint">
            Arrastra desde el borde derecho de una fase hasta otra para que dependa de ella.
          </p>
        </div>
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
              onRenamed={(id) => setSelection({ stage: id })}
              onDelete={() => setDeleting(selectedStage)}
              onEditYaml={(field) => editYaml(selectedStage, field)}
              onRevealProblem={reveal}
            />
          ) : selectedEdge ? (
            <section className="card stage-panel" aria-labelledby="edge-panel-title">
              <h2 id="edge-panel-title">
                <code>{selectedEdge.to}</code> depende de <code>{selectedEdge.from}</code>
              </h2>
              <div className="form">
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={edgeOptional === true}
                    onChange={(e) =>
                      apply((t) =>
                        setDependencyOptional(
                          t,
                          selectedEdge.to,
                          selectedEdge.from,
                          e.target.checked,
                        ),
                      )
                    }
                  />
                  Opcional: arranca aunque {selectedEdge.from} se omita o falle
                </label>
              </div>
              <div className="form-actions">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setSelection({ stage: selectedEdge.to })}
                >
                  Ver {selectedEdge.to}
                </Button>
                <Button
                  variant="secondary-danger"
                  size="sm"
                  onClick={() => {
                    if (apply((t) => removeDependency(t, selectedEdge.to, selectedEdge.from)))
                      setSelection(null)
                  }}
                >
                  <Trash2 size={16} aria-hidden="true" />
                  Quitar dependencia
                </Button>
              </div>
            </section>
          ) : (
            <section className="card stage-panel" aria-labelledby="graph-help-title">
              <h2 id="graph-help-title">Propiedades</h2>
              <p className="muted small">
                Elige una fase o una flecha para cambiarla, o añade una fase desde «Añadir fase». El
                nombre, los agentes y las entradas del workflow están en sus botones, encima del
                grafo.
              </p>
            </section>
          )}
        </div>
      </div>

      <Sheet
        open={deleting != null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`Borrar la fase ${deleting ?? ''}`}
        description="Se quita del YAML; hasta guardar, se puede deshacer."
      >
        {deleting && (
          <div className="form">
            {dependentsOf(text, deleting).length > 0 ? (
              <p>
                Dependen de ella {dependentsOf(text, deleting).join(', ')}: se les quita esa
                dependencia (y su <code>workspaceFrom</code>, si la continuaba).
              </p>
            ) : (
              <p>Ninguna fase depende de ella.</p>
            )}
            <div className="form-actions">
              <Button
                variant="danger"
                onClick={() => {
                  if (apply((t) => removeStage(t, deleting))) setSelection(null)
                  setDeleting(null)
                }}
              >
                <Trash2 size={18} aria-hidden="true" />
                Borrar fase
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
