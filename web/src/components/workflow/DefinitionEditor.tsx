import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useRef, type ReactNode } from 'react'
import { api, type Validation, type WorkflowModel } from '../../api'
import { LinesSkeleton } from '../ui/Skeleton'
import { DefinitionSummary } from './DefinitionSummary'
import { ProblemList } from './ProblemList'
import { problemSummary } from './problems'
import { useDebounced } from './useDebounced'
import type { YamlEditorHandle } from './YamlEditor'

const YamlEditor = lazy(() => import('./YamlEditor'))

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
 * valida (con los mismos mensajes que al guardar) y los problemas se marcan en su línea.
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
  const schema = useQuery({
    queryKey: ['workflow-schema'],
    queryFn: api.workflowSchema,
    staleTime: Infinity,
  })
  const debounced = useDebounced(text)
  const unchanged = saved != null && debounced === saved.text
  const live = useQuery({
    queryKey: ['workflow-validate', validateAs?.key, validateAs?.version, debounced],
    queryFn: () => api.validateWorkflow({ sourceYaml: debounced, ...validateAs }),
    enabled: !readOnly && !unchanged,
    placeholderData: keepPreviousData,
  })
  const current = unchanged || readOnly ? saved : live.data
  const problems = current?.validation.problems ?? []
  const definition = current?.definition ?? null

  return (
    <>
      {actions && <div className="definition-actions">{actions}</div>}
      <div className="definition-editor">
        <section className="definition-source" aria-label="YAML">
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
