import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Save } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { api, ApiError } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { Button } from '../components/ui/Button'
import { DefinitionEditor } from '../components/workflow/DefinitionEditor'
import { ImportButton } from '../components/workflow/ImportButton'
import { ProblemList } from '../components/workflow/ProblemList'

/** Punto de partida de un workflow nuevo: dos fases, la segunda continúa el worktree de la primera. */
export const TEMPLATE = `id: mi-workflow
name: Mi workflow
description: Qué hace este workflow.

inputs:
  foco:
    type: string
    required: false
    description: En qué fijarse.

agents:
  reviewer:
    prompt: |
      Revisa {{workItem.key}} ({{workItem.title}}) poniendo el foco en {{inputs.foco}}.
    tools: [Read, Grep, Glob]

stages:
  - id: review
    type: agent
    agent: reviewer
  - id: fix
    type: agent
    prompt: Corrige lo que encontró la revisión.
    dependsOn: [review]
`

/** Alta de un workflow: se escribe (o se importa) el YAML y se guarda como borrador de la v1. */
export function NewWorkflowPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [text, setText] = useState(TEMPLATE)
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
