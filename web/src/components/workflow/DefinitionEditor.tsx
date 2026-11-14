import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { CircleAlert, CircleCheck, Redo2, Undo2 } from 'lucide-react'
import { lazy, Suspense, useRef, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { api, type Problem, type Validation, type WorkflowModel } from '../../api'
import { cn } from '../../lib/cn'
import { LinesSkeleton } from '../ui/Skeleton'
import { TabList, TabPanel } from '../ui/Tabs'
import { AgentList, DefinitionOverview, LaunchInputs, ReadyItems } from './DefinitionOverview'
import { Companion } from '../companion/Companion'
import { ProblemList } from './ProblemList'
import { problemSummary } from './problems'
import { useDebounced } from './useDebounced'
import { StepsEditor } from './StepsEditor'
import { problemBadge } from '../graph/definitionProblems'
import { useTouchOnly } from '../../useTouchOnly'
import { useMediaQuery } from '../../useMediaQuery'
import { stageLine } from './definitionEdits'
import PlainYamlEditor from './PlainYamlEditor'
import { YamlToolbar } from './YamlToolbar'
import YamlView from './YamlView'
import type { YamlEditorHandle } from './YamlEditor'

const YamlEditor = lazy(() => import('./YamlEditor'))
// React Flow y dagre van en su propio trozo: solo se descargan al abrir el grafo.
const DefinitionGraph = lazy(() => import('../graph/DefinitionGraph'))
const GraphEditor = lazy(() => import('./GraphEditor').then((m) => ({ default: m.GraphEditor })))

/** Lo que se lee: el resumen (WF-E), el grafo y el YAML. */
const READ_VIEWS = [
  { id: 'summary', label: 'Resumen', icon: 'list' },
  { id: 'graph', label: 'Grafo', icon: 'graph' },
  { id: 'yaml', label: 'YAML', icon: 'code' },
] as const
/** Lo que se edita (WF-F): los pasos, el grafo y el YAML. */
const EDIT_VIEWS = [
  { id: 'steps', label: 'Pasos', icon: 'list' },
  { id: 'graph', label: 'Grafo', icon: 'graph' },
  { id: 'yaml', label: 'YAML', icon: 'code' },
] as const
type View = (typeof READ_VIEWS)[number]['id'] | (typeof EDIT_VIEWS)[number]['id']

interface Props {
  text: string
  onChange?: (text: string) => void
  readOnly?: boolean
  /** Validación de `text` tal como está guardado; se usa mientras no cambie. */
  saved?: { text: string; validation: Validation; definition: WorkflowModel | null }
  /** Workflow y versión del borrador, para validar como al guardarlo. */
  validateAs?: { key: string; version: number }
  onSave?: () => void
  label: string
  /** Botones sobre el editor (guardar, publicar, importar…). */
  actions?: ReactNode
  /** Nombre del fichero al descargar el YAML. */
  fileName?: string
}

/** Lo que dice la barra del borrador: sin problemas, o cuántos errores y avisos. */
function barStatus(problems: Problem[]): { tone: 'ok' | 'warn' | 'bad'; label: string } {
  const badge = problemBadge(problems)
  if (!badge) return { tone: 'ok', label: 'Sin problemas' }
  return { tone: badge.severity === 'ERROR' ? 'bad' : 'warn', label: badge.label }
}

/**
 * El YAML en el editor, con sus problemas y lo que define. Mientras se escribe, el control plane lo
 * valida (con los mismos mensajes que al guardar) y los problemas se marcan en su línea. La
 * pestaña «Grafo» (`?view=graph`) dibuja las fases de lo último que se pudo leer y, en un
 * borrador, se edita (W8-B): cada cambio del grafo es una edición del YAML, que se deshace igual.
 * En una pantalla estrecha, el grafo es de solo lectura.
 */
export function DefinitionEditor({
  text,
  onChange,
  readOnly = false,
  saved,
  validateAs,
  onSave,
  label,
  actions,
  fileName = 'workflow.yaml',
}: Props) {
  const editor = useRef<YamlEditorHandle>(null)
  const [params, setParams] = useSearchParams()
  // En un móvil, Monaco no deja seleccionar, copiar ni pegar: se usa un campo de texto normal.
  const touchOnly = useTouchOnly()
  const narrow = useMediaQuery('(max-width: 720px)')
  const editing = !readOnly && onChange != null
  const views = editing ? EDIT_VIEWS : READ_VIEWS
  // Una versión que solo se lee abre en «Resumen» (WF-E); un borrador, en «Pasos» (WF-F), salvo en
  // una pantalla estrecha, donde de momento se edita en el YAML.
  const defaultView: View = !editing ? 'summary' : narrow ? 'yaml' : 'steps'
  const asked = params.get('view')
  const view: View = views.find((v) => v.id === asked)?.id ?? defaultView
  const selectView = (next: View) =>
    setParams(
      (prev) => {
        const p = new URLSearchParams(prev)
        if (next === defaultView) p.delete('view')
        else p.set('view', next)
        return p
      },
      { replace: true },
    )
  // En el móvil las líneas largas se ajustan de entrada; en escritorio, a petición.
  const [wrapChoice, setWrap] = useState<boolean | null>(null)
  const wrap = wrapChoice ?? narrow
  const graphEditable = editing && !narrow
  const schema = useQuery({
    queryKey: ['workflow-schema'],
    queryFn: api.workflowSchema,
    staleTime: Infinity,
  })
  const debounced = useDebounced(text)
  const unchanged = saved != null && debounced === saved.text
  const live = useQuery({
    queryKey: ['workflow-validate', validateAs?.key, validateAs?.version, debounced],
    // Con el texto validado: mientras llega la validación nueva se enseña la anterior, y los
    // problemas dicen su fase por posición en ese texto.
    queryFn: async () => ({
      ...(await api.validateWorkflow({ sourceYaml: debounced, ...validateAs })),
      text: debounced,
    }),
    enabled: !readOnly && !unchanged,
    placeholderData: keepPreviousData,
  })
  // Hasta que llega la primera validación del texto cambiado, la de lo guardado: así el grafo no
  // desaparece (ni pierde la fase elegida) al empezar a editar.
  const current = unchanged || readOnly ? saved : (live.data ?? saved)
  const problems = current?.validation.problems ?? []
  const definition = current?.definition ?? null
  const validating = !current || (!readOnly && !unchanged && live.isFetching)
  const skyny = validating
    ? 'thinking'
    : problems.some((p) => p.severity === 'ERROR')
      ? 'error'
      : 'calm'
  // Desde el grafo, «Editar en YAML» y los problemas llevan a su línea en la pestaña YAML.
  const revealInYaml = (line: number, column: number) => {
    selectView('yaml')
    // El editor estaba oculto: se le da el foco cuando ya se ve.
    requestAnimationFrame(() => editor.current?.reveal(line, column))
  }

  const tabs = (
    <TabList
      label="Vista de la definición"
      tabs={views}
      selected={view}
      onSelect={selectView}
      idPrefix="definition-view"
      panelId="definition-view-panel"
    />
  )

  return (
    <>
      {editing ? (
        // La barra del borrador (WF-F): modo, deshacer, rehacer, cómo está y guardar, siempre a
        // la vista.
        <div className="definition-bar">
          {tabs}
          {!touchOnly && (
            <span className="definition-history">
              <button
                type="button"
                className="icon-btn"
                aria-label="Deshacer"
                title="Deshacer (Ctrl+Z)"
                onClick={() => editor.current?.undo?.()}
              >
                <Undo2 size={20} strokeWidth={2.25} aria-hidden="true" />
              </button>
              <button
                type="button"
                className="icon-btn"
                aria-label="Rehacer"
                title="Rehacer (Ctrl+Mayús+Z)"
                onClick={() => editor.current?.redo?.()}
              >
                <Redo2 size={20} strokeWidth={2.25} aria-hidden="true" />
              </button>
            </span>
          )}
          <span
            className={cn(
              'definition-bar-status',
              current ? `is-${barStatus(problems).tone}` : 'is-thinking',
            )}
          >
            {current && barStatus(problems).tone === 'ok' ? (
              <CircleCheck size={16} strokeWidth={2.5} aria-hidden="true" />
            ) : (
              <CircleAlert size={16} strokeWidth={2.5} aria-hidden="true" />
            )}
            {current ? barStatus(problems).label : 'Validando…'}
          </span>
          {actions && <div className="definition-actions">{actions}</div>}
        </div>
      ) : (
        actions && <div className="definition-actions">{actions}</div>
      )}
      <div
        className={cn(
          'definition-editor',
          (view === 'graph' || view === 'steps') && 'definition-editor-graph',
        )}
      >
        <section className="definition-source" aria-label="Definición">
          {!editing && tabs}
          {view === 'steps' && (
            <TabPanel
              id="definition-view-panel"
              labelledBy="definition-view-steps"
              className="definition-steps"
            >
              {definition && !narrow && onChange ? (
                <StepsEditor
                  text={text}
                  onChange={onChange}
                  definition={definition}
                  problems={problems}
                  source={current?.text ?? text}
                  reveal={revealInYaml}
                  lineOf={(id, field) => stageLine(text, id, field)}
                  keyEditable={validateAs == null}
                  undo={touchOnly ? undefined : () => editor.current?.undo?.()}
                  redo={touchOnly ? undefined : () => editor.current?.redo?.()}
                />
              ) : definition ? (
                <>
                  <p className="muted small">
                    En una pantalla estrecha los pasos son de solo lectura: edita en la pestaña
                    YAML.
                  </p>
                  <DefinitionOverview definition={definition} />
                </>
              ) : (
                <p className="muted">
                  {current
                    ? 'El YAML no se puede leer: corrige los problemas para ver los pasos.'
                    : 'Validando…'}
                </p>
              )}
            </TabPanel>
          )}
          {view === 'summary' && (
            <TabPanel
              id="definition-view-panel"
              labelledBy="definition-view-summary"
              className="definition-overview"
            >
              {definition ? (
                <DefinitionOverview definition={definition} />
              ) : (
                <p className="muted">
                  {current
                    ? 'El YAML no se puede leer: corrige los problemas para ver el resumen.'
                    : 'Validando…'}
                </p>
              )}
            </TabPanel>
          )}
          {view === 'graph' && (
            <TabPanel
              id="definition-view-panel"
              labelledBy="definition-view-graph"
              className="definition-graph"
            >
              {definition && graphEditable ? (
                <Suspense fallback={<LinesSkeleton label="el grafo" lines={6} />}>
                  <GraphEditor
                    text={text}
                    onChange={onChange}
                    definition={definition}
                    problems={problems}
                    source={current?.text ?? text}
                    reveal={revealInYaml}
                    lineOf={(id, field) => stageLine(text, id, field)}
                    keyEditable={validateAs == null}
                    undo={touchOnly ? undefined : () => editor.current?.undo?.()}
                    redo={touchOnly ? undefined : () => editor.current?.redo?.()}
                  />
                </Suspense>
              ) : definition ? (
                <Suspense fallback={<LinesSkeleton label="el grafo" lines={6} />}>
                  {!readOnly && onChange && narrow && (
                    <p className="muted small">
                      En una pantalla estrecha el grafo es de solo lectura: edita en la pestaña
                      YAML.
                    </p>
                  )}
                  <DefinitionGraph
                    definition={definition}
                    problems={problems}
                    source={current?.text ?? text}
                  />
                </Suspense>
              ) : (
                <p className="muted">
                  {current
                    ? 'El YAML no se puede leer: corrige los problemas para ver el grafo.'
                    : 'Validando…'}
                </p>
              )}
            </TabPanel>
          )}
          {/* El editor sigue montado en el grafo, para no perder el cursor ni el deshacer. */}
          <div
            className="definition-yaml"
            hidden={view !== 'yaml'}
            {...(view === 'yaml' && {
              role: 'tabpanel',
              id: 'definition-view-panel',
              'aria-labelledby': 'definition-view-yaml',
            })}
          >
            <YamlToolbar text={text} fileName={fileName} wrap={wrap} onWrapChange={setWrap} />
            {touchOnly && readOnly ? (
              <YamlView ref={editor} value={text} label={label} wrap={wrap} />
            ) : touchOnly ? (
              <PlainYamlEditor
                ref={editor}
                value={text}
                onChange={onChange}
                readOnly={readOnly}
                label={label}
                onSave={onSave}
                wrap={wrap}
              />
            ) : (
              <Suspense fallback={<LinesSkeleton label="el editor" lines={12} />}>
                <YamlEditor
                  ref={editor}
                  value={text}
                  onChange={onChange}
                  readOnly={readOnly}
                  problems={problems}
                  schema={schema.data}
                  label={label}
                  onSave={onSave}
                  wrap={wrap}
                />
              </Suspense>
            )}
          </div>
        </section>
        <aside className="definition-aside">
          <section className="card" aria-labelledby="problems-title">
            <div className="problems-head">
              <h2 id="problems-title">Listo para lanzar</h2>
              {/* Skyny pone cara de lo que ve: pensando al validar, error si hay errores (SY-C). */}
              <Companion state={skyny} size={36} still season={null} />
            </div>
            <p className="muted small" aria-live="polite">
              {current ? problemSummary(problems) : 'Validando…'}
            </p>
            {current && <ReadyItems definition={definition} problems={problems} />}
            {problems.length > 0 && (
              <ProblemList
                problems={problems}
                onSelect={(line, column) => revealInYaml(line, column)}
              />
            )}
          </section>
          {definition && view === 'summary' && (
            <>
              <section className="card" aria-labelledby="inputs-title">
                <h2 id="inputs-title">Al lanzar te pide</h2>
                <LaunchInputs definition={definition} />
              </section>
              <section className="card" aria-labelledby="agents-title">
                <h2 id="agents-title">Agentes</h2>
                <AgentList definition={definition} />
              </section>
            </>
          )}
        </aside>
      </div>
    </>
  )
}
