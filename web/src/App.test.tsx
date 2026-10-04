import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { mockFetch, renderAt } from './test/render'

class FakeEventSource {
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((m: MessageEvent) => void) | null = null
  close() {}
}

describe('App', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lista los proyectos y muestra el control plane disponible', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/actuator/health': { status: 'UP' },
          '/api/projects': [
            { id: 'p1', key: 'TKM', name: 'TokenMeter', description: null, createdAt: '' },
          ],
        }),
      ),
    )
    renderAt('/', <App />)
    expect(await screen.findByText('TokenMeter')).toBeInTheDocument()
    expect(await screen.findByText('disponible')).toBeInTheDocument()
  })

  it('muestra el control plane no disponible si la petición falla', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
    renderAt('/', <App />)
    expect(await screen.findByText('no disponible')).toBeInTheDocument()
  })

  it('muestra la ejecución con su fase, su agente y el prompt', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/actuator/health': { status: 'UP' },
          '/api/workflow-runs/r1': {
            id: 'r1',
            workItemId: 'w1',
            status: 'RUNNING',
            createdAt: '2026-10-04T10:00:00Z',
            startedAt: null,
            finishedAt: null,
            stages: [
              {
                id: 's1',
                stageKey: 'agent',
                status: 'READY',
                attempt: 1,
                startedAt: null,
                finishedAt: null,
                agents: [
                  {
                    id: 'a1',
                    stageRunId: 's1',
                    repositoryId: 'repo',
                    kind: 'START',
                    status: 'QUEUED',
                    provider: 'claude-code',
                    createdAt: '2026-10-04T10:00:00Z',
                  },
                ],
              },
            ],
          },
          '/api/agent-runs/a1': {
            agent: {},
            workflowRunId: 'r1',
            prompts: [
              {
                id: 'pr1',
                role: 'user',
                content: 'Implementa X',
                sha256: 'abcdef0123456789',
                createdAt: '',
              },
            ],
          },
        }),
      ),
    )
    renderAt('/runs/r1', <App />)
    expect(await screen.findByText('En cola')).toBeInTheDocument()
    expect(screen.getByText('Preparada')).toBeInTheDocument()
    expect(await screen.findByText('Implementa X')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument()
  })
})
