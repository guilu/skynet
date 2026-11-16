import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  MemoryCurationReport,
  MemoryObservation,
  WikiPage,
  WikiRevision,
  WikiSummary,
} from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const entry = (id: string, title: string): MemoryObservation => ({
  id,
  projectId: 'p1',
  repositoryId: 'r1',
  kind: 'DECISION',
  title,
  topicKey: null,
  what: 'Las PRs van con squash',
  why: null,
  where: null,
  learned: null,
  tags: [],
  origin: 'HUMAN',
  sourceWorkflowRunId: null,
  sourceAgentRunId: null,
  pinned: false,
  redacted: false,
  version: 1,
  createdAt: '2026-11-10T10:00:00Z',
  updatedAt: '2026-11-10T10:00:00Z',
  updatedBy: 'admin',
  archivedAt: null,
  trust: 'CONFIRMED',
  trustReason: null,
})

const summary = (over: Partial<WikiSummary>): WikiSummary => ({
  id: 'w1',
  repositoryId: null,
  slug: 'indice',
  title: 'Índice',
  origin: 'GENERATED',
  version: 3,
  sources: 0,
  updatedAt: '2026-11-15T10:00:00Z',
  updatedBy: 'skynet',
  ...over,
})

const index: WikiPage = {
  id: 'w1',
  projectId: 'p1',
  repositoryId: null,
  slug: 'indice',
  title: 'Índice',
  body: 'La wiki de TokenMeter.\n\nOtras páginas:\n\n- [Arquitectura](arquitectura)\n- [Fuera](javascript:alert(1))',
  sources: [],
  origin: 'GENERATED',
  version: 3,
  createdAt: '2026-11-10T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
  updatedBy: 'skynet',
}

const architecture: WikiPage = {
  ...index,
  id: 'w2',
  repositoryId: 'r1',
  slug: 'arquitectura',
  title: 'Arquitectura',
  body: '## Piezas\n\nUna sola aplicación.',
  sources: [
    { kind: 'memory', id: 'm1', label: 'Fusionar con squash', url: null },
    {
      kind: 'pull_request',
      id: 'pr1',
      label: 'TKM-1 · PR #40',
      url: 'https://github.com/o/r/pull/40',
    },
  ],
  origin: 'CURATOR',
  version: 2,
  updatedBy: 'curador',
}

const revisions: WikiRevision[] = [
  { ...architecture, createdAt: architecture.updatedAt, createdBy: 'curador' },
  {
    version: 1,
    title: 'Arquitectura',
    body: 'Primera versión.',
    sources: [],
    origin: 'CURATOR',
    createdAt: '2026-11-12T10:00:00Z',
    createdBy: 'curador',
  },
]

const curation: MemoryCurationReport = {
  id: 'c1',
  projectId: 'p1',
  repositoryId: 'r1',
  workflowRunId: 'run9',
  agentRunId: 'a9',
  summary: 'Dos decisiones repetidas y una configuración vieja.',
  pages: [
    { slug: 'arquitectura', title: 'Arquitectura', scope: 'repository', version: 2, changed: true },
    { slug: 'modulos', title: 'Módulos', scope: 'project', version: 1, changed: false },
  ],
  rejected: [{ item: 'indice', reason: 'Esta página la genera Skynet' }],
  proposals: [
    {
      id: 'pr-merge',
      curationId: 'c1',
      action: 'MERGE',
      observationIds: ['m2'],
      intoId: 'm1',
      reason: 'Dicen lo mismo',
      status: 'PENDING',
      decidedBy: null,
      decidedAt: null,
      createdAt: '2026-11-15T10:00:00Z',
    },
    {
      id: 'pr-archive',
      curationId: 'c1',
      action: 'ARCHIVE',
      observationIds: ['m3'],
      intoId: null,
      reason: 'Ya compila con 21',
      status: 'REJECTED',
      decidedBy: 'admin',
      decidedAt: '2026-11-15T11:00:00Z',
      createdAt: '2026-11-15T10:00:00Z',
    },
  ],
  createdAt: '2026-11-15T10:00:00Z',
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

describe('Wiki y curador de la memoria', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña la wiki por ámbitos, abre una página y sigue sus enlaces', async () => {
    stubApi({
      '/api/projects/p1/wiki': [
        summary({ id: 'w3', slug: 'hecho', title: 'Lo hecho' }),
        summary({}),
        summary({
          id: 'w2',
          repositoryId: 'r1',
          slug: 'arquitectura',
          title: 'Arquitectura',
          origin: 'CURATOR',
          version: 2,
          sources: 2,
        }),
      ],
      '/api/projects/p1/wiki/indice': index,
      '/api/projects/p1/wiki/indice/revisions': [],
      '/api/projects/p1/wiki/arquitectura?repositoryId=r1': architecture,
      '/api/projects/p1/wiki/arquitectura/revisions?repositoryId=r1': revisions,
      '/api/memory/m1': entry('m1', 'Fusionar con squash'),
      '/api/memory/m1/history': [],
      '/api/memory/m1/readers': [],
    })
    renderAt('/projects/p1?tab=memory&view=wiki', <App />)

    const project = await screen.findByRole('region', { name: 'Todo el proyecto' })
    const titles = within(project)
      .getAllByRole('button')
      .map((b) => b.querySelector('strong')?.textContent)
    expect(titles).toEqual(['Índice', 'Lo hecho'])
    const repo = screen.getByRole('region', { name: 'tokenmeter' })
    expect(within(repo).getByText('del curador')).toBeInTheDocument()
    expect(repo).toHaveTextContent('v2 · 2 fuentes')

    fireEvent.click(within(project).getByRole('button', { name: /Índice/ }))
    const sheet = await screen.findByRole('dialog', { name: 'Página de la wiki' })
    const body = await within(sheet).findByRole('article', { name: 'Contenido de Índice' })
    expect(body).toHaveTextContent('La wiki de TokenMeter.')
    expect(within(body).queryByRole('link')).toBeNull()
    expect(within(sheet).getByText(/no se edita a mano/)).toBeInTheDocument()

    fireEvent.click(within(body).getByRole('button', { name: 'Arquitectura' }))
    expect(await within(sheet).findByRole('heading', { name: 'Arquitectura' })).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: 'TKM-1 · PR #40' })).toHaveAttribute(
      'href',
      'https://github.com/o/r/pull/40',
    )
    expect(await within(sheet).findByText('Primera versión.')).toBeInTheDocument()

    fireEvent.click(within(sheet).getByRole('button', { name: 'Fusionar con squash' }))
    const detail = await screen.findByRole('dialog', { name: 'Entrada de la memoria' })
    expect(
      await within(detail).findByRole('heading', { name: 'Fusionar con squash' }),
    ).toBeInTheDocument()
  })

  it('regenera las páginas de Skynet', async () => {
    const fetch = stubApi({
      '/api/projects/p1/wiki': [],
      'POST /api/projects/p1/wiki/refresh': [summary({})],
    })
    renderAt('/projects/p1?tab=memory&view=wiki', <App />)

    expect(await screen.findByText('La wiki del proyecto está vacía.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Regenerar las páginas de Skynet' }))
    expect(await screen.findByRole('region', { name: 'Todo el proyecto' })).toBeInTheDocument()
    expect(
      fetch.mock.calls.some(
        ([url, init]) => url === '/api/projects/p1/wiki/refresh' && init?.method === 'POST',
      ),
    ).toBe(true)
  })

  it('enseña el informe del curador y acepta una propuesta', async () => {
    const fetch = stubApi({
      '/api/projects/p1/memory/curations': [curation],
      '/api/memory/m1': entry('m1', 'Fusionar con squash'),
      '/api/memory/m2': entry('m2', 'Las PRs van con squash'),
      '/api/memory/m3': entry('m3', 'Usar Java 17'),
      'POST /api/memory/proposals/pr-merge/accept': {
        ...curation.proposals[0],
        status: 'ACCEPTED',
        decidedBy: 'admin',
        decidedAt: '2026-11-16T08:00:00Z',
      },
    })
    renderAt('/projects/p1?tab=memory', <App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Curador' }))
    const report = await screen.findByRole('article', { name: /Curación del/ })
    expect(report).toHaveTextContent('Dos decisiones repetidas y una configuración vieja.')
    expect(report).toHaveTextContent('1 propuesta pendiente')
    expect(report).toHaveTextContent('Módulos modulos · todo el proyecto · v1 · sin cambios')
    expect(report).toHaveTextContent('Esta página la genera Skynet')
    expect(within(report).getByRole('link', { name: 'Ver la ejecución' })).toHaveAttribute(
      'href',
      '/runs/run9',
    )

    const merge = within(report).getByRole('region', { name: 'Fusionar entradas' })
    expect(await within(merge).findByRole('button', { name: 'Fusionar con squash' })).toBeVisible()
    expect(within(merge).getByRole('button', { name: 'Las PRs van con squash' })).toBeVisible()
    const archive = within(report).getByRole('region', { name: 'Archivar entradas' })
    expect(archive).toHaveTextContent('Rechazada por admin')
    expect(within(archive).queryByRole('button', { name: 'Aceptar' })).toBeNull()

    fetch.mockClear()
    fireEvent.click(within(merge).getByRole('button', { name: 'Aceptar' }))
    await waitFor(() =>
      expect(
        fetch.mock.calls.some(
          ([url, init]) =>
            url === '/api/memory/proposals/pr-merge/accept' && init?.method === 'POST',
        ),
      ).toBe(true),
    )
    await waitFor(() =>
      expect(fetch.mock.calls.map(([url]) => String(url))).toContain(
        '/api/projects/p1/memory/curations',
      ),
    )
  })

  it('sin informes, explica cómo lanzar el curador', async () => {
    stubApi({ '/api/projects/p1/memory/curations': [] })
    renderAt('/projects/p1?tab=memory&view=curations', <App />)

    expect(
      await screen.findByText('El curador todavía no ha repasado la memoria.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/Curar la memoria/)).toBeInTheDocument()
  })
})
