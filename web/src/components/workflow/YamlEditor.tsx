import { configureMonacoYaml, type MonacoYaml, type MonacoYamlOptions } from 'monaco-yaml'
import 'monaco-editor/languages/definitions/yaml/register'
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react'
import type { Problem } from '../../api'
import { BASE_OPTIONS, defineTheme, followTheme, monaco } from '../../lib/monaco'

const OWNER = 'skynet'
const SCHEMA_URI = 'https://skynet.dev/schemas/workflow-definition.schema.json'

let yaml: MonacoYaml | null = null

/**
 * Autocompletado y ayuda al pasar por encima a partir del JSON Schema. La validación la hace el
 * control plane (los mismos mensajes que al guardar), así que la de monaco-yaml va apagada.
 */
function configureYaml(schema: object | undefined) {
  const options: MonacoYamlOptions = {
    enableSchemaRequest: false,
    validate: false,
    completion: true,
    hover: true,
    schemas: schema ? [{ uri: SCHEMA_URI, fileMatch: ['*'], schema: schema as never }] : [],
  }
  if (yaml) yaml.update(options)
  else yaml = configureMonacoYaml(monaco, options)
}

const SEVERITY: Record<Problem['severity'], monaco.MarkerSeverity> = {
  ERROR: monaco.MarkerSeverity.Error,
  UNSUPPORTED: monaco.MarkerSeverity.Warning,
  WARNING: monaco.MarkerSeverity.Info,
}

export interface YamlEditorHandle {
  /** Lleva el cursor a esa posición y le da el foco. */
  reveal: (line: number, column: number) => void
}

interface Props {
  value: string
  onChange?: (value: string) => void
  readOnly?: boolean
  /** Problemas que se marcan en el texto. */
  problems: Problem[]
  /** JSON Schema para el autocompletado; sin él, el editor funciona igual. */
  schema?: object
  label: string
  /** Ctrl/Cmd+S. */
  onSave?: () => void
  height?: number
}

/** Editor del YAML de un workflow, con los problemas marcados en su línea. */
const YamlEditor = forwardRef<YamlEditorHandle, Props>(function YamlEditor(
  { value, onChange, readOnly = false, problems, schema, label, onSave, height = 520 },
  ref,
) {
  const container = useRef<HTMLDivElement>(null)
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)
  const latest = useRef({ onChange, onSave })
  latest.current = { onChange, onSave }
  // Textos que el editor ya ha entregado y que aún pueden volver como `value` con retraso: si se
  // escribe deprisa, React puede pintar un valor anterior al del modelo, y no hay que pisarlo.
  const emitted = useRef(new Set<string>())

  useEffect(() => {
    defineTheme()
    const model = monaco.editor.createModel(
      value,
      'yaml',
      monaco.Uri.parse('inmemory://workflow.yaml'),
    )
    const instance = monaco.editor.create(container.current!, {
      ...BASE_OPTIONS,
      model,
      readOnly,
      ariaLabel: label,
      tabSize: 2,
      insertSpaces: true,
      quickSuggestions: { other: true, strings: false, comments: false },
    })
    editor.current = instance
    const changes = model.onDidChangeContent(() => {
      const text = model.getValue()
      emitted.current.add(text)
      latest.current.onChange?.(text)
    })
    instance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () =>
      latest.current.onSave?.(),
    )
    const unfollow = followTheme()
    return () => {
      unfollow()
      changes.dispose()
      model.dispose()
      instance.dispose()
      editor.current = null
    }
    // El editor se crea una vez; el texto, la lectura y los problemas se actualizan aparte.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => configureYaml(schema), [schema])

  useEffect(() => {
    const model = editor.current?.getModel()
    if (!model) return
    if (model.getValue() === value) emitted.current.clear()
    else if (!emitted.current.has(value)) {
      // Un texto de fuera (importar un archivo): se aplica como una edición, que se puede deshacer.
      emitted.current.clear()
      model.pushEditOperations([], [{ range: model.getFullModelRange(), text: value }], () => null)
    }
  }, [value])

  useEffect(() => {
    editor.current?.updateOptions({ readOnly, ariaLabel: label })
  }, [readOnly, label])

  useEffect(() => {
    const model = editor.current?.getModel()
    if (!model) return
    monaco.editor.setModelMarkers(
      model,
      OWNER,
      problems
        .filter((p) => p.line != null)
        .map((p) => {
          const line = Math.min(p.line!, model.getLineCount())
          const column = p.column ?? 1
          const word = model.getWordAtPosition({ lineNumber: line, column })
          return {
            severity: SEVERITY[p.severity],
            message: p.message,
            startLineNumber: line,
            startColumn: column,
            endLineNumber: line,
            endColumn: word ? word.endColumn : model.getLineMaxColumn(line),
          }
        }),
    )
  }, [problems, value])

  useImperativeHandle(ref, () => ({
    reveal(line, column) {
      const instance = editor.current
      if (!instance) return
      instance.revealLineInCenter(line)
      instance.setPosition({ lineNumber: line, column })
      instance.focus()
    },
  }))

  return <div ref={container} className="monaco-view yaml-editor" style={{ height }} />
})

export default YamlEditor
