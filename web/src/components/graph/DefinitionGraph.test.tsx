import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Problem, StageDefinition, WorkflowModel } from '../../api'
import DefinitionGraph from './DefinitionGraph'

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
})

const definition: WorkflowModel = {
  key: 'demo',
  name: null,
  description: null,
  inputs: [],
  agents: [],
  stages: [stage('plan', ['spec']), stage('build', ['plan']), stage('review', ['build'])],
}

const source = `id: demo
stages:
  - id: plan
    dependsOn: [spec]
  - id: build
    dependsOn: [plan]
  - id: review
    dependsOn: [build]
`

const problems: Problem[] = [
  {
    severity: 'ERROR',
    path: 'stages.plan.dependsOn[0]',
    line: 4,
    column: 17,
    message: 'No hay ninguna fase `spec`',
  },
  {
    severity: 'ERROR',
    path: 'stages.build.prompt',
    line: 5,
    column: 5,
    message: 'Falta el prompt',
  },
  { severity: 'WARNING', path: 'stages[1].agent', line: 5, column: 5, message: 'Un aviso' },
  { severity: 'WARNING', path: 'stages.review.agent', line: 7, column: 5, message: 'Otro aviso' },
]

const box = (graph: HTMLElement, id: string) =>
  graph.querySelector<HTMLElement>(`.react-flow__node[data-id="${id}"]`)!

describe('DefinitionGraph', () => {
  it('pone los problemas de cada fase encima y la dependencia que no existe como fantasma', async () => {
    render(<DefinitionGraph definition={definition} problems={problems} source={source} />)
    const graph = await screen.findByRole('group', { name: 'Grafo de demo' })

    const plan = box(graph, 'plan')
    expect(within(plan).getByText('1 error')).toBeInTheDocument()
    expect(plan).toHaveClass('graph-has-errors')
    const build = box(graph, 'build')
    expect(within(build).getByText('1 error').parentElement).toHaveAttribute(
      'title',
      'Falta el prompt\nUn aviso',
    )
    const review = box(graph, 'review')
    expect(within(review).getByText('1 aviso')).toBeInTheDocument()
    expect(review).not.toHaveClass('graph-has-errors')

    const ghost = box(graph, 'spec')
    expect(ghost).toHaveClass('graph-ghost')
    expect(within(ghost).getByText('No existe')).toBeInTheDocument()
    expect(ghost).toHaveTextContent('la nombra plan')
  })

  it('un ciclo pone la insignia en todas sus fases, no solo donde lo cuenta el control plane', async () => {
    const cyclic = {
      ...definition,
      stages: [stage('plan'), stage('build', ['plan', 'review']), stage('review', ['build'])],
    }
    const cycle: Problem = {
      severity: 'ERROR',
      path: 'stages.build.dependsOn',
      line: 5,
      column: 5,
      message: 'Las dependencias forman un ciclo: build → review → build',
    }
    render(<DefinitionGraph definition={cyclic} problems={[cycle]} source={source} />)
    const graph = await screen.findByRole('group', { name: 'Grafo de demo' })
    for (const id of ['build', 'review']) {
      const node = box(graph, id)
      expect(within(node).getByText('1 error').parentElement).toHaveAttribute(
        'title',
        cycle.message,
      )
      expect(node).toHaveClass('graph-has-errors')
    }
    expect(box(graph, 'plan').querySelector('.graph-problems')).toBeNull()
  })

  it('sin problemas no hay insignias ni fantasmas', async () => {
    const clean = { ...definition, stages: [stage('plan'), stage('build', ['plan'])] }
    render(<DefinitionGraph definition={clean} />)
    const graph = await screen.findByRole('group', { name: 'Grafo de demo' })
    expect(graph.querySelector('.graph-problems')).toBeNull()
    expect(graph.querySelector('.graph-ghost')).toBeNull()
  })
})
