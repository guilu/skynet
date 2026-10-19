import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Code, Network } from 'lucide-react'
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
import PlainYamlEditor from './PlainYamlEditor'
import type { YamlEditorHandle } from './YamlEditor'

const YamlEditor = lazy(() => import('./YamlEditor'))
// React Flow y dagre van en su propio trozo: solo se descargan al abrir el grafo.
const DefinitionGraph = lazy(() => import('../graph/DefinitionGraph'))

const VIEWS = [
  { id: 'yaml', label: 'YAML', icon: Code },
  { id: 'graph', label: 'Grafo', icon: Network },
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
 * pestaña «Grafo» (`?view=graph`) dibuja las fases de lo último que se pudo leer.
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
  const current = unchanged || readOnly ? saved : live.data
  const problems = current?.validation.problems ?? []
  const definition = current?.definition ?? null

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
              {definition ? (
                <Suspense fallback={<LinesSkeleton label="el grafo" lines={6} />}>
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
