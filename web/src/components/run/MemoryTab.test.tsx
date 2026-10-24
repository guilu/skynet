import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentMemory, AgentRun } from '../../api'
import { mockFetch, renderAt } from '../../test/render'
import { MemoryTab } from './MemoryTab'

const agent = { id: 'a1' } as AgentRun

const access = (over: Partial<AgentMemory['accesses'][number]>) => ({
  observationId: 'm1',
  version: 1,
  via: 'PROMPT' as const,
  kind: 'DECISION' as const,
  title: 'Fusionar con squash',
  repositoryId: 'r1',
  archived: false,
  times: 1,
  firstAt: '2026-10-21T10:00:00Z',
  ...over,
})

describe('Memoria del agente', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('separa lo que recibió, lo que buscó o leyó y lo que guardó, con su versión', async () => {
    const memory: AgentMemory = {
      agentRunId: 'a1',
      projectId: 'p1',
      mode: 'WRITE',
      accesses: [
        access({}),
        access({ via: 'SEARCH', times: 2 }),
        access({
          observationId: 'm2',
          version: 3,
          via: 'SAVE',
          kind: 'PITFALL',
          title: 'Gradle cachea',
        }),
      ],
    }
    vi.stubGlobal('fetch', vi.fn(mockFetch({ '/api/agent-runs/a1/memory': memory })))
    renderAt('/', <MemoryTab agent={agent} />)

    const received = await screen.findByRole('region', { name: 'Lo que recibió' })
    expect(within(received).getByRole('link', { name: 'Fusionar con squash' })).toHaveAttribute(
      'href',
      '/projects/p1?tab=memory&entry=m1',
    )
    expect(received).toHaveTextContent('v1 · en el bloque del prompt')
    expect(screen.getByRole('region', { name: 'Lo que buscó o leyó' })).toHaveTextContent(
      'en una búsqueda (2 veces)',
    )
    const saved = screen.getByRole('region', { name: 'Lo que guardó' })
    expect(saved).toHaveTextContent('Gradle cachea · Trampa · v3 · con mem_save')
  })

  it('dice cuándo la invocación no usó la memoria', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/api/agent-runs/a1/memory': {
            agentRunId: 'a1',
            projectId: 'p1',
            mode: null,
            accesses: [],
          },
        }),
      ),
    )
    renderAt('/', <MemoryTab agent={agent} />)
    expect(await screen.findByText(/no usó la memoria/)).toBeInTheDocument()
  })
})
