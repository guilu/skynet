import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { parse } from 'yaml'
import type { Problem, StageDefinition, WorkflowModel } from '../../api'
import { GraphEditor } from './GraphEditor'

const TEXT = `id: demo
agents:
  dev:
    prompt: Implementa
stages:
  - id: plan
    type: agent
    agent: dev
  - id: build   # implementa
    type: agent
    prompt: Implementa
    dependsOn: [plan]
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
})

const definition: WorkflowModel = {
  key: 'demo',
  name: null,
  description: null,
  inputs: [],
  agents: [],
  stages: [stage('plan'), stage('build', ['plan'])],
}

const stagesOf = (text: string) => (parse(text) as { stages: Record<string, unknown>[] }).stages

/** El editor con el texto en un estado, como en la página. */
function Harness({
  problems = [],
  reveal = () => {},
  keyEditable = false,
}: {
  problems?: Problem[]
  reveal?: () => void
  keyEditable?: boolean
}) {
  const [text, setText] = useState(TEXT)
  return (
    <>
      <GraphEditor
        text={text}
        onChange={setText}
        definition={definition}
        problems={problems}
        source={TEXT}
        reveal={reveal}
        lineOf={() => ({ line: 9, column: 5 })}
        keyEditable={keyEditable}
      />
      <pre data-testid="yaml">{text}</pre>
    </>
  )
}

const yaml = () => screen.getByTestId('yaml').textContent!
const node = async (id: string) => {
  const graph = await screen.findByRole('group', { name: 'Grafo de demo' })
  return within(graph.querySelector<HTMLElement>(`.react-flow__node[data-id="${id}"]`)!).getByRole(
    'button',
  )
}

/** Escribe en el campo y pulsa Intro, que lo aplica. */
const type = (field: HTMLElement, value: string) => {
  fireEvent.change(field, { target: { value } })
  fireEvent.keyDown(field, { key: 'Enter' })
}

describe('GraphEditor', () => {
  it('añade una fase de la paleta detrás de la elegida y abre su panel', async () => {
    render(<Harness />)
    fireEvent.click(await node('build'))
    expect(await node('build')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(screen.getByLabelText('Añadir fase'), { target: { value: 'command' } })
    expect(stagesOf(yaml()).map((s) => s.id)).toEqual(['plan', 'build', 'command'])
    expect(stagesOf(yaml())[2]).toEqual({
      id: 'command',
      type: 'command',
      command: '',
      dependsOn: ['build'],
    })
    expect(yaml()).toContain('  - id: build   # implementa\n')
    expect(screen.getByRole('heading', { name: 'Fase command' })).toBeInTheDocument()

    type(screen.getByLabelText('Comando'), './gradlew test')
    expect(stagesOf(yaml())[2].command).toBe('./gradlew test')
  })

  it('renombra una fase desde su panel y actualiza lo que la nombra', async () => {
    render(<Harness />)
    fireEvent.click(await node('plan'))
    type(screen.getByLabelText('Id'), 'planificar')
    expect(stagesOf(yaml()).map((s) => [s.id, s.dependsOn])).toEqual([
      ['planificar', undefined],
      ['build', ['planificar']],
    ])
    expect(screen.getByRole('heading', { name: 'Fase planificar' })).toBeInTheDocument()
  })

  it('rechaza una dependencia que cerraría un ciclo y no toca el YAML', async () => {
    render(<Harness />)
    fireEvent.click(await node('plan'))
    fireEvent.change(screen.getByLabelText('Fase de la que dependerá'), {
      target: { value: 'build' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Añadir dependencia' }))
    expect(screen.getByRole('status')).toHaveTextContent(
      'Así se formaría un ciclo: plan → build → plan',
    )
    expect(yaml()).toBe(TEXT)
  })

  it('cambia y quita dependencias desde el panel', async () => {
    render(<Harness />)
    fireEvent.click(await node('build'))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Opcional' }))
    expect(yaml()).toContain('dependsOn: ["plan?"]')
    fireEvent.click(screen.getByRole('button', { name: 'Quitar la dependencia de plan' }))
    expect(stagesOf(yaml())[1].dependsOn).toBeUndefined()
  })

  it('borra una fase tras confirmar, diciendo qué dependía de ella', async () => {
    render(<Harness />)
    fireEvent.click(await node('plan'))
    fireEvent.click(screen.getByRole('button', { name: 'Borrar fase…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Borrar la fase plan' })
    expect(dialog).toHaveTextContent('Dependen de ella build')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Borrar fase' }))
    expect(stagesOf(yaml()).map((s) => [s.id, s.dependsOn])).toEqual([['build', undefined]])
    expect(screen.getByRole('heading', { name: 'Propiedades' })).toBeInTheDocument()
  })

  it('marca el campo con su problema y lleva a su línea del YAML', async () => {
    const reveal = vi.fn()
    const problems: Problem[] = [
      {
        severity: 'ERROR',
        path: 'stages.build.agent',
        line: 9,
        column: 5,
        message: 'No hay ningún agente `revisor`',
      },
    ]
    render(<Harness problems={problems} reveal={reveal} />)
    fireEvent.click(await node('build'))
    const agent = screen.getByLabelText('Agente')
    expect(agent).toHaveAttribute('aria-invalid', 'true')
    expect(agent).toHaveAccessibleDescription('No hay ningún agente `revisor`')
    fireEvent.click(screen.getByRole('button', { name: /No hay ningún agente/ }))
    expect(reveal).toHaveBeenCalledWith(9, 5)
    fireEvent.click(screen.getByRole('button', { name: 'Editar en YAML' }))
    expect(reveal).toHaveBeenCalledTimes(2)
  })

  it('edita el nombre del workflow y, solo en el alta, su id', async () => {
    const { unmount } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Workflow' }))
    expect(screen.getByRole('button', { name: 'Workflow' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByLabelText('Id')).toBeNull()
    type(screen.getByLabelText('Nombre'), 'Demo E2E')
    expect(yaml()).toContain('id: demo\nname: Demo E2E\nagents:')
    unmount()
    render(<Harness keyEditable />)
    fireEvent.click(screen.getByRole('button', { name: 'Workflow' }))
    type(screen.getByLabelText('Id'), 'otro')
    expect(yaml().startsWith('id: otro\n')).toBe(true)
  })

  it('añade, configura, renombra y quita agentes; no quita uno que se usa', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Agentes' }))
    fireEvent.click(screen.getByRole('button', { name: 'Quitar dev' }))
    expect(screen.getByRole('status')).toHaveTextContent('No se puede quitar `dev`: lo usa plan')
    type(screen.getByLabelText('Nombre de dev'), 'autor')
    expect(stagesOf(yaml())[0].agent).toBe('autor')
    fireEvent.click(screen.getByRole('button', { name: 'Añadir agente' }))
    const panel = screen.getByRole('region', { name: 'Agentes' })
    const agente = within(panel).getByText('agente').closest('details')!
    fireEvent.change(within(agente).getByLabelText('Proveedor'), { target: { value: 'codex' } })
    type(within(agente).getByLabelText('Modelo'), 'gpt-5.5')
    type(within(agente).getByLabelText('Herramientas'), 'Read, Bash(git:*)')
    type(within(agente).getByLabelText('Turnos máximos'), '12')
    const agents = (parse(yaml()) as { agents: Record<string, unknown> }).agents
    expect(agents.agente).toEqual({
      prompt: '',
      provider: 'codex',
      model: 'gpt-5.5',
      tools: ['Read', 'Bash(git:*)'],
      limits: { maxTurns: 12 },
    })
    fireEvent.click(within(agente).getByRole('button', { name: 'Quitar agente' }))
    expect(Object.keys((parse(yaml()) as { agents: object }).agents)).toEqual(['autor'])
  })

  it('añade una entrada, la renombra con sus usos y la hace obligatoria', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Entradas' }))
    expect(screen.getByText('Sin entradas: al lanzar no se pide nada.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Añadir entrada' }))
    type(screen.getByLabelText('Nombre de dato'), 'tema')
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'number' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Obligatoria' }))
    type(screen.getByLabelText('Valor por defecto'), '3')
    expect((parse(yaml()) as { inputs: object }).inputs).toEqual({
      tema: { type: 'number', required: true, default: 3 },
    })
    // Elegir una fase cierra el panel de entradas.
    fireEvent.click(await node('plan'))
    expect(screen.getByRole('button', { name: 'Entradas' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    expect(screen.getByRole('heading', { name: 'Fase plan' })).toBeInTheDocument()
  })
})
