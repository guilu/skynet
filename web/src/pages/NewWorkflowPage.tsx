import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Save } from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api, ApiError } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { Button } from '../components/ui/Button'
import { DefinitionEditor } from '../components/workflow/DefinitionEditor'
import { ImportButton } from '../components/workflow/ImportButton'
import { ProblemList } from '../components/workflow/ProblemList'
import { TEMPLATES } from '../components/workflow/templates'

/** Alta de un workflow: se escribe (o se importa) el YAML y se guarda como borrador de la v1. */
export function NewWorkflowPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [, setParams] = useSearchParams()
  const [template, setTemplate] = useState(TEMPLATES[0])
  const [text, setText] = useState(template.yaml)
  const chooseTemplate = (id: string) => {
    const next = TEMPLATES.find((t) => t.id === id)
    if (!next) return
    // Lo escrito encima de la plantilla se perdería: solo con permiso.
    if (text !== template.yaml && !window.confirm('Se sustituye el YAML que has escrito. ¿Seguir?'))
      return
    setTemplate(next)
    setText(next.yaml)
    // «Desde cero» es para montarlo en el grafo: se abre esa pestaña.
    if (next.id === 'desde-cero')
      setParams(
        (prev) => {
          const p = new URLSearchParams(prev)
          p.set('view', 'graph')
          return p
        },
        { replace: true },
      )
  }
  const create = useMutation({
    mutationFn: () => api.createWorkflow(text),
    onSuccess: (detail) => {
      void queryClient.invalidateQueries({ queryKey: ['workflows'] })
      void navigate(`/workflows/${encodeURIComponent(detail.key)}`)
    },
  })
  const problems = create.error instanceof ApiError ? create.error.problems : []

  return (
    <>
      <div className="page-head">
        <div className="page-title">
          <h1>Nuevo workflow</h1>
          <p className="muted small">
            Se guarda como borrador aunque tenga errores; se publica cuando no tenga ninguno. El
            formato está en <code>docs/workflows.md</code>.
          </p>
        </div>
      </div>
      <div className="form template-picker">
        <label>
          Plantilla
          <select value={template.id} onChange={(e) => chooseTemplate(e.target.value)}>
            {TEMPLATES.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        <p className="hint">{template.description}</p>
      </div>
      <DefinitionEditor
        text={text}
        onChange={setText}
        label="YAML del workflow nuevo"
        onSave={() => create.mutate()}
        actions={
          <>
            <Button onClick={() => create.mutate()} disabled={create.isPending}>
              <Save size={18} strokeWidth={2.5} aria-hidden="true" />
              Crear borrador
            </Button>
            <ImportButton onText={setText} />
          </>
        }
      />
      <ErrorMessage error={create.error} />
      {problems.length > 0 && <ProblemList problems={problems} />}
    </>
  )
}
