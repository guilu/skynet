import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useRef, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { api, type Validation, type WorkflowModel } from '../../api'
import { cn } from '../../lib/cn'
import { LinesSkeleton } from '../ui/Skeleton'
import { TabList, TabPanel } from '../ui/Tabs'
import { DefinitionSummary } from './DefinitionSummary'
import { ProblemList } from './ProblemList'
import { problemSummary } from './problems'
import { useDebounced } from './useDebounced'
import { useTouchOnly } from '../../useTouchOnly'
import { useMediaQuery } from '../../useMediaQuery'
import { stageLine } from './definitionEdits'
import PlainYamlEditor from './PlainYamlEditor'
import type { YamlEditorHandle } from './YamlEditor'

const YamlEditor = lazy(() => import('./YamlEditor'))
// React Flow y dagre van en su propio trozo: solo se descargan al abrir el grafo.
const DefinitionGraph = lazy(() => import('../graph/DefinitionGraph'))
const GraphEditor = lazy(() => import('./GraphEditor').then((m) => ({ default: m.GraphEditor })))

const VIEWS = [
  { id: 'yaml', label: 'YAML', icon: 'code' },
  { id: 'graph', label: 'Grafo', icon: 'graph' },
] as const
type View = (typeof VIEWS)[number]['id']

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
}: Props) {
  const editor = useRef<YamlEditorHandle>(null)
  const [params, setParams] = useSearchParams()
  const view: View = params.get('view') === 'graph' ? 'graph' : 'yaml'
  const selectView = (next: View) =>
    setParams(
      (prev) => {
        const p = new URLSearchParams(prev)
        if (next === 'graph') p.set('view', 'graph')
        else p.delete('view')
        return p
      },
      { replace: true },
    )
  // En un móvil, Monaco no deja seleccionar, copiar ni pegar: se usa un campo de texto normal.
  const touchOnly = useTouchOnly()
  const narrow = useMediaQuery('(max-width: 720px)')
  const graphEditable = !readOnly && onChange != null && !narrow
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
  // Desde el grafo, «Editar en YAML» y los problemas llevan a su línea en la pestaña YAML.
  const revealInYaml = (line: number, column: number) => {
    selectView('yaml')
    // El editor estaba oculto: se le da el foco cuando ya se ve.
    requestAnimationFrame(() => editor.current?.reveal(line, column))
  }

  return (
    <>
      {actions && <div className="definition-actions">{actions}</div>}
      <div className={cn('definition-editor', view === 'graph' && 'definition-editor-graph')}>
        <section className="definition-source" aria-label="Definición">
          <TabList
            label="Vista de la definición"
            tabs={VIEWS}
            selected={view}
            onSelect={selectView}
            idPrefix="definition-view"
            panelId="definition-view-panel"
          />
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
            {touchOnly ? (
              <PlainYamlEditor
                ref={editor}
                value={text}
                onChange={onChange}
                readOnly={readOnly}
                label={label}
                onSave={onSave}
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
                />
              </Suspense>
            )}
          </div>
        </section>
        <aside className="definition-aside">
          <section className="card" aria-labelledby="problems-title">
            <h2 id="problems-title">Problemas</h2>
            <p className="muted small" aria-live="polite">
              {current ? problemSummary(problems) : 'Validando…'}
            </p>
            {problems.length > 0 && (
              <ProblemList
                problems={problems}
                onSelect={(line, column) => editor.current?.reveal(line, column)}
              />
            )}
          </section>
          {definition && (
            <section className="card" aria-labelledby="summary-title">
              <h2 id="summary-title">Qué define</h2>
              <DefinitionSummary definition={definition} />
            </section>
          )}
        </aside>
      </div>
    </>
  )
}
