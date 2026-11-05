import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import changes from '../../fixtures/contracts/changes.json'
import deliveries from '../../fixtures/contracts/github-deliveries.json'
import gitHubStatus from '../../fixtures/contracts/github-status.json'
import providers from '../../fixtures/contracts/providers.json'
import runPage from '../../fixtures/contracts/run-page.json'
import runners from '../../fixtures/contracts/runners.json'
import summaries from '../../fixtures/contracts/work-item-summaries.json'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const failed = {
  ...runPage.items[0],
  id: 'run-failed',
  status: 'FAILED',
  workItemKey: 'DEMO-9',
  finishedAt: new Date().toISOString(),
}

function stubApi(routes: Record<string, unknown> = {}) {
  const base = mockFetch({
    '/actuator/health': { status: 'UP' },
    '/api/auth/session': { username: 'admin' },
    '/api/work-items': summaries,
    '/api/results': changes,
    '/api/providers': providers,
    '/api/runners': runners,
    '/api/github': gitHubStatus,
    '/api/github/deliveries?limit=50': deliveries,
    ...routes,
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString()
      // La consulta de las fallidas lleva la hora: se responde por el estado.
      if (url.includes('/api/workflow-runs?status=PENDING'))
        return new Response(JSON.stringify(runPage))
      if (url.includes('/api/workflow-runs?status=FAILED'))
        return new Response(JSON.stringify({ items: [failed], page: 0, size: 10, total: 1 }))
      return base(input, init)
    }),
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('Fábrica y Administración (FS-F)', () => {
  it('En curso agrupa las ejecuciones por proyecto › repositorio con su barra de fases', async () => {
    stubApi()
    renderAt('/runs', <App />)
    const group = await screen.findByRole('region', { name: 'Analizador de texto › demo-repo' })
    expect(within(group).getByRole('link', { name: /TKM-1/ })).toHaveAttribute(
      'href',
      `/runs/${runPage.items[0].id}`,
    )
    expect(within(group).getByRole('img', { name: /^Fases:/ })).toBeInTheDocument()
    expect(screen.getByText(/1 ejecución en marcha/)).toBeInTheDocument()
    const unresolved = screen.getByRole('region', { name: 'Terminadas sin resultado' })
    expect(within(unresolved).getByRole('link', { name: /DEMO-9 falló/ })).toBeInTheDocument()
    const today = screen.getByRole('region', { name: 'Resultados de hoy' })
    expect(within(today).getByRole('link', { name: 'Todos los resultados' })).toHaveAttribute(
      'href',
      '/results',
    )
    expect(screen.getByRole('tab', { name: 'En curso' })).toHaveAttribute('aria-selected', 'true')
  })

  it('un filtro del historial en la URL abre la pestaña Historial', async () => {
    stubApi({ '/api/workflow-runs?status=FAILED&page=0&size=20': runPage })
    renderAt('/runs?status=FAILED', <App />)
    expect(await screen.findByRole('tab', { name: 'Historial' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('Resultados lista las PRs con su trabajo y su proyecto', async () => {
    stubApi()
    renderAt('/results', <App />)
    const table = await screen.findByRole('table', { name: 'Resultados' })
    const c = changes[0]
    expect(within(table).getByRole('link', { name: new RegExp(c.workItemKey) })).toHaveAttribute(
      'href',
      `/work-items/${c.workItemId}`,
    )
    expect(within(table).getByRole('link', { name: c.projectName })).toBeInTheDocument()
    expect(screen.getByText(/1 PR abierta/)).toBeInTheDocument()
  })

  it('Proveedores cruza los CLIs con los runners y lleva la reserva por cuota', async () => {
    stubApi({
      '/api/settings/quota': {
        enabled: true,
        maxWaitHours: 24,
        maxWaits: 3,
        fallbackAfterMinutes: 20,
        updatedAt: null,
      },
      '/api/quota/waits': [],
    })
    renderAt('/providers', <App />)
    const table = await screen.findByRole('table', { name: 'Proveedores' })
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('Claude Code')
    expect(rows[1]).toHaveTextContent('runner-01 · 2.1.288')
    expect(rows[3]).toHaveTextContent('Sin runner')
    expect(
      await screen.findByRole('heading', { name: 'Continuar tras la cuota' }),
    ).toBeInTheDocument()
  })

  it('Administración tiene una tarjeta por sección', async () => {
    stubApi()
    renderAt('/admin', <App />)
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Administración' }),
    ).toBeInTheDocument()
    for (const [name, href] of [
      ['Proveedores', '/providers'],
      ['Runners', '/runners'],
      ['Consumo', '/usage'],
      ['Ajustes', '/settings'],
      ['Mantenimiento', '/maintenance'],
    ]) {
      const heading = screen.getByRole('heading', { level: 2, name })
      expect(within(heading).getByRole('link')).toHaveAttribute('href', href)
    }
    expect(await screen.findByText('1 en línea')).toBeInTheDocument()
  })

  it('Mantenimiento junta lo archivado y las entregas del webhook', async () => {
    stubApi({
      '/api/projects?archived=true': [
        {
          id: 'p9',
          key: 'OLD',
          name: 'Antiguo',
          description: null,
          createdAt: '2026-10-01T00:00:00Z',
          archivedAt: '2026-10-20T00:00:00Z',
        },
      ],
      '/api/workflows?archived=true': [],
      '/api/runners?archived=true': [],
    })
    renderAt('/maintenance', <App />)
    const projects = await screen.findByRole('region', { name: 'Proyectos archivados' })
    expect(await within(projects).findByRole('link', { name: 'OLD · Antiguo' })).toHaveAttribute(
      'href',
      '/projects/p9',
    )
    expect(
      within(screen.getByRole('region', { name: 'Workflows archivados' })).getByText('Nada.'),
    ).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Últimas entregas' })).toBeInTheDocument()
  })
})
