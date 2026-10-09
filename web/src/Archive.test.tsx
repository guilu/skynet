import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import runPage from '../../fixtures/contracts/run-page.json'
import type { DeletionCounts, DeletionPreview } from './api'
import App from './App'
import { describeCounts } from './components/archive/counts'
import { mockFetch, renderAt } from './test/render'

const archivedProject = {
  id: 'p1',
  key: 'TKM',
  name: 'TokenMeter',
  description: null,
  archivedAt: '2026-10-09T08:00:00Z',
}

const counts: DeletionCounts = {
  repositories: 1,
  workItems: 2,
  runs: 3,
  agents: 4,
  artifacts: 2,
  artifactBytes: 12 * 1024,
  events: 40,
}

const blocked: DeletionPreview = {
  deletable: false,
  blockers: ['Quedan 2 worktrees en el runner.'],
  warnings: [],
  liveWorkspaces: 2,
  counts,
}

/** Como mockFetch, pero las rutas se pueden cambiar a mitad de prueba (p. ej. la vista previa). */
function stubApi(routes: Record<string, unknown>) {
  const all: Record<string, unknown> = {
    '/actuator/health': { status: 'UP' },
    '/api/auth/session': { username: 'admin' },
    ...routes,
  }
  const fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => mockFetch(all)(input, init))
  vi.stubGlobal('fetch', fetch)
  return { fetch, routes: all }
}

const calls = (fetch: ReturnType<typeof vi.fn>, method: string) =>
  fetch.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([url]) => String(url))

describe('Archivar y eliminar', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('un proyecto archivado es de solo lectura, se puede restaurar y eliminar', async () => {
    const { fetch, routes } = stubApi({
      '/api/projects/p1': archivedProject,
      '/api/projects/p1/work-items': [],
      '/api/projects/p1/repositories': [],
      '/api/projects/p1/deletion-preview': blocked,
      'POST /api/projects/p1/workspaces/cleanup': { requested: 2 },
      'DELETE /api/projects/p1': counts,
      '/api/projects': [],
    })
    renderAt('/projects/p1', <App />)

    expect(await screen.findByText(/Archivado el/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Nuevo trabajo' })).toBeNull()

    fireEvent.keyDown(screen.getByRole('button', { name: 'Más acciones: TKM · TokenMeter' }), {
      key: 'Enter',
    })
    expect(await screen.findByRole('menuitem', { name: 'Restaurar' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Eliminar…' }))

    const sheet = await screen.findByRole('dialog', { name: 'Eliminar el proyecto' })
    expect(await within(sheet).findByText(/1 repositorio, 2 trabajos, 3 ejecuciones/)).toBeVisible()
    expect(within(sheet).getByRole('alert')).toHaveTextContent('Quedan 2 worktrees en el runner.')
    const confirm = within(sheet).getByRole('button', { name: 'Eliminar definitivamente' })
    expect(confirm).toBeDisabled()

    routes['/api/projects/p1/deletion-preview'] = {
      ...blocked,
      deletable: true,
      blockers: [],
      liveWorkspaces: 0,
    }
    fireEvent.click(within(sheet).getByRole('button', { name: 'Eliminar los 2 worktrees' }))
    await waitFor(() => expect(confirm).toBeEnabled())
    expect(calls(fetch, 'POST')).toContain('/api/projects/p1/workspaces/cleanup')

    fireEvent.click(confirm)
    expect(await screen.findByRole('heading', { name: 'Proyectos' })).toBeInTheDocument()
    expect(calls(fetch, 'DELETE')).toEqual(['/api/projects/p1'])
  })

  it('el chip «Archivados» de proyectos pide solo lo archivado', async () => {
    const { fetch } = stubApi({
      '/api/projects': [],
      '/api/projects?archived=true': [archivedProject],
    })
    renderAt('/projects', <App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Archivados' }))
    expect(await screen.findByRole('link', { name: /TokenMeter/ })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/projects?archived=true', expect.anything())
    expect(screen.getByRole('button', { name: 'Archivados' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('archiva en bloque las ejecuciones seleccionadas', async () => {
    const run = runPage.items[0]
    const other = { ...run, id: 'r2', workItemKey: 'TKM-2' }
    const { fetch } = stubApi({
      '/api/workflow-runs?page=0&size=25': { ...runPage, items: [run, other], total: 2 },
      [`POST /api/workflow-runs/${run.id}/archive`]: { archivedAt: '2026-10-09T08:00:00Z' },
      'POST /api/workflow-runs/r2/archive': { archivedAt: '2026-10-09T08:00:00Z' },
    })
    renderAt('/runs', <App />)

    fireEvent.click(await screen.findByRole('checkbox', { name: /^Seleccionar TKM-1/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /^Seleccionar TKM-2/ }))
    const bar = screen.getByRole('group', { name: 'Acciones con las ejecuciones seleccionadas' })
    expect(bar).toHaveTextContent('2 seleccionadas')
    fireEvent.click(within(bar).getByRole('button', { name: 'Archivar' }))

    await waitFor(() =>
      expect(calls(fetch, 'POST')).toEqual([
        `/api/workflow-runs/${run.id}/archive`,
        '/api/workflow-runs/r2/archive',
      ]),
    )
    await waitFor(() => expect(screen.queryByRole('group', { name: /seleccionadas/ })).toBeNull())
  })

  it('cuenta lo que se borraría en palabras', () => {
    expect(describeCounts({ ...counts, repositories: 0, workItems: 0, events: 0 })).toBe(
      '3 ejecuciones, 4 agentes, 2 artefactos (12 KB)',
    )
    expect(
      describeCounts({
        repositories: 0,
        workItems: 0,
        runs: 0,
        agents: 0,
        artifacts: 0,
        artifactBytes: 0,
        events: 0,
      }),
    ).toBe('Nada más que el propio registro')
  })
})
