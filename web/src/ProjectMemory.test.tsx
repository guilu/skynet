import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MemoryObservation } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const decision: MemoryObservation = {
  id: 'm1',
  projectId: 'p1',
  repositoryId: 'r1',
  kind: 'DECISION',
  title: 'Fusionar con squash',
  topicKey: 'git/merge',
  what: 'Las PRs van con squash',
  why: 'El historial de main queda limpio',
  where: null,
  learned: null,
  tags: ['git'],
  origin: 'AGENT',
  sourceWorkflowRunId: 'run1',
  sourceAgentRunId: 'a1',
  pinned: false,
  redacted: true,
  version: 2,
  createdAt: '2026-10-20T10:00:00Z',
  updatedAt: '2026-10-21T10:00:00Z',
  updatedBy: null,
  archivedAt: null,
}

const runLog: MemoryObservation = {
  ...decision,
  id: 'm2',
  kind: 'RUN_LOG',
  title: 'Ejecución revisar v1 de TKM-1: terminada bien',
  topicKey: 'bitacora/run1',
  what: 'Workflow: revisar v1',
  why: null,
  tags: ['bitacora'],
  origin: 'SYSTEM',
  redacted: false,
  version: 1,
  repositoryId: null,
  pinned: true,
}

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/projects/p1': { id: 'p1', key: 'TKM', name: 'TokenMeter', description: null },
      '/api/projects/p1/work-items': [],
      '/api/projects/p1/repositories': [{ id: 'r1', name: 'tokenmeter', agentPolicy: {} }],
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

describe('Memoria del proyecto', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña la línea de tiempo, con las fijadas primero, y filtra', async () => {
    const fetch = stubApi({
      '/api/projects/p1/memory?size=50': { items: [runLog, decision], page: 0, size: 50, total: 2 },
      '/api/projects/p1/memory?repository=r1&kind=DECISION&size=50': {
        items: [decision],
        page: 0,
        size: 50,
        total: 1,
      },
    })
    renderAt('/projects/p1?tab=memory', <App />)

    const timeline = await screen.findByRole('list', { name: 'Memoria del proyecto' })
    const headings = within(timeline)
      .getAllByRole('heading')
      .map((h) => h.textContent)
    expect(headings[0]).toBe('Fijadas')
    expect(
      within(timeline).getByText('Ejecución revisar v1 de TKM-1: terminada bien'),
    ).toBeInTheDocument()
    expect(within(timeline).getByText(/tokenmeter · v2 · de un agente/)).toBeInTheDocument()
    expect(within(timeline).getByText(/Todo el proyecto · v1 · de Skynet/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Repositorio'), { target: { value: 'r1' } })
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'DECISION' } })
    await waitFor(() =>
      expect(fetch.mock.calls.map(([url]) => String(url))).toContain(
        '/api/projects/p1/memory?repository=r1&kind=DECISION&size=50',
      ),
    )
    await waitFor(() => expect(screen.queryByText(/terminada bien/)).toBeNull())
  })

  it('abre una entrada con su procedencia y sus versiones, y la fija', async () => {
    const fetch = stubApi({
      '/api/projects/p1/memory?size=50': { items: [decision], page: 0, size: 50, total: 1 },
      '/api/memory/m1': decision,
      '/api/memory/m1/history': [
        {
          ...decision,
          observationId: 'm1',
          version: 2,
          changedBy: null,
          changedAt: decision.updatedAt,
        },
        {
          ...decision,
          observationId: 'm1',
          version: 1,
          what: 'Las PRs van con merge',
          changedBy: null,
          changedAt: decision.createdAt,
        },
      ],
      'POST /api/memory/m1/pin': { ...decision, pinned: true },
    })
    renderAt('/projects/p1?tab=memory', <App />)

    fireEvent.click(await screen.findByRole('button', { name: /Fusionar con squash/ }))
    const sheet = await screen.findByRole('dialog', { name: 'Entrada de la memoria' })
    expect(
      await within(sheet).findByRole('heading', { name: 'Fusionar con squash' }),
    ).toBeInTheDocument()
    expect(within(sheet).getByRole('note')).toHaveTextContent('Se enmascaró un secreto')
    expect(within(sheet).getByRole('link', { name: 'Ver la ejecución' })).toHaveAttribute(
      'href',
      '/runs/run1',
    )
    expect(within(sheet).getByRole('link', { name: 'Ver la invocación' })).toHaveAttribute(
      'href',
      '/agent-runs/a1',
    )
    expect(await within(sheet).findByText('Las PRs van con merge')).toBeInTheDocument()

    fireEvent.click(within(sheet).getByRole('button', { name: 'Fijar' }))
    expect(await within(sheet).findByRole('button', { name: 'Desfijar' })).toBeInTheDocument()
    expect(
      fetch.mock.calls.some(
        ([url, init]) => url === '/api/memory/m1/pin' && init?.method === 'POST',
      ),
    ).toBe(true)
  })

  it('edita una entrada y crea una versión nueva', async () => {
    const fetch = stubApi({
      '/api/projects/p1/memory?size=50': { items: [decision], page: 0, size: 50, total: 1 },
      '/api/memory/m1': decision,
      '/api/memory/m1/history': [],
      'PUT /api/memory/m1': { ...decision, version: 3, what: 'Squash siempre', origin: 'HUMAN' },
    })
    renderAt('/projects/p1?tab=memory&entry=m1', <App />)

    const sheet = await screen.findByRole('dialog', { name: 'Entrada de la memoria' })
    fireEvent.click(await within(sheet).findByRole('button', { name: 'Editar' }))
    fireEvent.change(within(sheet).getByLabelText('Qué'), { target: { value: 'Squash siempre' } })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Guardar versión' }))

    expect(await within(sheet).findByText('v3')).toBeInTheDocument()
    const put = fetch.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(put[1]!.body as string)).toEqual({
      kind: 'DECISION',
      title: 'Fusionar con squash',
      topicKey: 'git/merge',
      what: 'Squash siempre',
      why: 'El historial de main queda limpio',
      where: null,
      learned: null,
      tags: ['git'],
    })
  })
})
