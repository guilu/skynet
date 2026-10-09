import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Repository } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const repository: Repository = {
  id: 'repo1',
  projectId: 'p1',
  name: 'demo',
  localPath: '/r',
  remoteUrl: null,
  defaultBranch: 'main',
  validationCommand: null,
  testReportPaths: [],
  agentPolicy: {
    allowedTools: ['Read', 'Glob', 'Grep', 'Edit', 'Write'],
    permissionMode: 'dontAsk',
    environment: null,
    maxTurns: null,
    maxBudgetUsd: 2,
    timeoutMinutes: 30,
  },
  agentPolicyCustom: false,
  createdAt: '',
}

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/projects/p1': { id: 'p1', key: 'TKM', name: 'TokenMeter', description: null },
      '/api/projects/p1/work-items': [],
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const renderRepos = () => renderAt('/projects/p1?tab=repos', <App />)

const sent = (fetch: ReturnType<typeof stubApi>, method: string) =>
  fetch.mock.calls.find(([, init]) => init?.method === method)

describe('Política de agentes del repositorio', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parte de la global y guarda una propia', async () => {
    const custom = {
      ...repository,
      agentPolicyCustom: true,
      agentPolicy: { ...repository.agentPolicy, allowedTools: ['Read', 'Bash(git:*)'] },
    }
    const fetch = stubApi({
      '/api/projects/p1/repositories': [repository],
      'PUT /api/projects/p1/repositories/repo1/agent-policy': custom,
    })
    renderRepos()
    expect(await screen.findByText('global')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Editar política de demo' }))
    expect(screen.getByLabelText(/Herramientas permitidas/)).toHaveValue(
      'Read\nGlob\nGrep\nEdit\nWrite',
    )
    fireEvent.change(screen.getByLabelText(/Herramientas permitidas/), {
      target: { value: 'Read\n Bash(git:*) \n\n' },
    })
    fireEvent.change(screen.getByLabelText('Modo de permisos'), {
      target: { value: 'acceptEdits' },
    })
    fireEvent.click(screen.getByLabelText(/todas las variables que permite el runner/))
    fireEvent.change(screen.getByLabelText(/Variables de entorno que recibe/), {
      target: { value: 'JAVA_HOME' },
    })
    fireEvent.change(screen.getByLabelText('Turnos (vacío: sin límite)'), {
      target: { value: '20' },
    })
    fireEvent.change(screen.getByLabelText('Presupuesto (US$)'), { target: { value: '1.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar política' }))

    await waitFor(() => expect(sent(fetch, 'PUT')).toBeDefined())
    expect(JSON.parse(sent(fetch, 'PUT')![1]!.body as string)).toEqual({
      allowedTools: ['Read', 'Bash(git:*)'],
      permissionMode: 'acceptEdits',
      environment: ['JAVA_HOME'],
      maxTurns: 20,
      maxBudgetUsd: 1.5,
      timeoutMinutes: 30,
    })
    expect(await screen.findByRole('status')).toHaveTextContent('Guardado.')
  })

  it('una política propia puede volver a la global', async () => {
    const custom = { ...repository, agentPolicyCustom: true }
    const fetch = stubApi({
      '/api/projects/p1/repositories': [custom],
      'DELETE /api/projects/p1/repositories/repo1/agent-policy': repository,
    })
    renderRepos()
    expect(await screen.findByText('propia')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Editar política de demo' }))
    fireEvent.click(screen.getByRole('button', { name: 'Volver a la política global' }))

    await waitFor(() => expect(sent(fetch, 'DELETE')).toBeDefined())
    expect(sent(fetch, 'DELETE')![0]).toBe('/api/projects/p1/repositories/repo1/agent-policy')
  })
})
