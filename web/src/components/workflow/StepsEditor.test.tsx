import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { parse } from 'yaml'
import type { Problem, StageDefinition, WorkflowModel } from '../../api'
import { StepsEditor } from './StepsEditor'

const TEXT = `id: demo
agents:
  dev:
    prompt: Implementa
stages:
  - id: plan
    type: agent
    agent: dev
  - id: build
    type: agent
    prompt: Implementa
    dependsOn: [plann]
`

const stage = (id: string, dependsOn: string[] = []): StageDefinition => ({
  id,
  name: null,
  type: 'agent',
  agent: null,
  prompt: 'Haz algo',
  dependsOn: dependsOn.map((s) => ({ stage: s, optional: false })),
  workspace: 'INHERIT',
  workspaceFrom: null,
  command: null,
  children: [],
  condition: null,
  outputSchema: null,
  outputSchemaPath: null,
  checks: [],
  approval: null,
  retry: null,
  timeoutSeconds: null,
  phase: null,
  memory: null,
  pullRequest: null,
  ci: null,
  merge: null,
  judge: null,
})

const definition: WorkflowModel = {
  key: 'demo',
  name: null,
  description: null,
  risk: { paths: [], patterns: [] },
  inputs: [],
  agents: [],
  stages: [stage('plan'), stage('build', ['plan'])],
}

const typo: Problem = {
  severity: 'ERROR',
  path: 'stages.build.dependsOn[0]',
  line: 12,
  column: 17,
  message: 'No hay ninguna fase `plann`; ¿quisiste decir `plan`?',
}

const stagesOf = (text: string) =>
  (parse(text) as { stages: { id: string; dependsOn?: string[] }[] }).stages

function Harness({ problems = [], narrow = false }: { problems?: Problem[]; narrow?: boolean }) {
  const [text, setText] = useState(TEXT)
  return (
    <>
      <StepsEditor
        text={text}
        onChange={setText}
        definition={definition}
        problems={problems}
        source={TEXT}
        reveal={() => {}}
        lineOf={() => ({ line: 6, column: 5 })}
        narrow={narrow}
      />
      <pre data-testid="yaml">{text}</pre>
    </>
  )
}

const yaml = () => screen.getByTestId('yaml').textContent!

describe('el editor por pasos (WF-F)', () => {
  it('cuenta los pasos y, al elegir uno, abre su panel', () => {
    render(<Harness />)
    const steps = screen.getByRole('region', { name: 'Pasos' })
    expect(
      within(steps)
        .getAllByRole('listitem')
        .filter((li) => li.classList.contains('wf-step')),
    ).toHaveLength(2)
    fireEvent.click(within(steps).getByRole('button', { name: '2. build' }))
    expect(screen.getByRole('heading', { name: 'Fase build' })).toBeInTheDocument()
  })

  it('un error dice su paso y se arregla en un clic', () => {
    render(<Harness problems={[typo]} />)
    const alert = screen.getByRole('region', { name: /No se puede publicar: 1 error/ })
    expect(alert).toHaveTextContent('Paso 2 · build · línea 12')
    expect(within(screen.getByRole('region', { name: 'Pasos' })).getByText('1 error')).toBeVisible()
    fireEvent.click(within(alert).getByRole('button', { name: 'Cambiar a «plan»' }))
    expect(stagesOf(yaml())[1].dependsOn).toEqual(['plan'])
  })

  it('Alt+↑ mueve el paso elegido cuando es solo cambiar el orden, y si no dice por qué', () => {
    render(<Harness />)
    const steps = screen.getByRole('region', { name: 'Pasos' })
    const build = within(steps).getByRole('button', { name: '2. build' })
    // `build` depende de `plann`, que no existe: no va «detrás de plan» y solo cambia el orden.
    fireEvent.keyDown(build, { key: 'ArrowUp', altKey: true })
    expect(stagesOf(yaml()).map((s) => s.id)).toEqual(['build', 'plan'])
    fireEvent.keyDown(build, { key: 'ArrowUp', altKey: true })
    expect(screen.getByRole('status')).toHaveTextContent('Ya es el primer paso')
  })

  it('en el móvil, cada paso se edita en una hoja y «Añadir paso» abre la paleta en otra (WF-G)', () => {
    render(<Harness problems={[typo]} narrow />)
    const steps = screen.getByRole('region', { name: 'Pasos' })
    const row = within(steps).getByRole('button', { name: /2\. build/ })
    expect(row).toHaveTextContent('1 error')
    fireEvent.click(row)
    const sheet = screen.getByRole('dialog', { name: '2. build' })
    expect(within(sheet).getByRole('heading', { name: 'Fase build' })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Subir' }))
    expect(stagesOf(yaml()).map((s) => s.id)).toEqual(['build', 'plan'])
    fireEvent.click(within(sheet).getByRole('button', { name: 'Hecho' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(
      within(steps)
        .getAllByRole('button', { name: /Añadir paso/ })
        .at(-1)!,
    )
    const palette = screen.getByRole('dialog', { name: 'Añadir paso' })
    fireEvent.click(within(palette).getByRole('button', { name: /Comando/ }))
    expect(stagesOf(yaml()).map((s) => s.id)).toContain('comando')
    expect(screen.getByRole('dialog', { name: /comando/ })).toBeInTheDocument()
  })
})
