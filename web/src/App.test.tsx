import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import dashboardSummary from '../../fixtures/contracts/dashboard-summary.json'
import runPage from '../../fixtures/contracts/run-page.json'
import runView from '../../fixtures/contracts/run-view.json'
import runners from '../../fixtures/contracts/runners.json'
import App from './App'
import { mockFetch, renderAt } from './test/render'

class FakeEventSource {
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((m: MessageEvent) => void) | null = null
  close() {}
}

// Las vistas de ejemplo son las mismas que valida ContractIT en el backend.
const runningRun = runPage.items[0]
const agentId = runningRun.stages[0].agents[0].id

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(mockFetch({ '/actuator/health': { status: 'UP' }, ...routes }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.documentElement.removeAttribute('data-theme')
  })

  it('lista los proyectos y muestra el control plane disponible', async () => {
    stubApi({
      '/api/projects': [
        { id: 'p1', key: 'TKM', name: 'TokenMeter', description: null, createdAt: '' },
      ],
    })
    renderAt('/projects', <App />)
    expect(await screen.findByText('TokenMeter')).toBeInTheDocument()
    expect(await screen.findByText('disponible')).toBeInTheDocument()
  })

  it('muestra el control plane no disponible si la petición falla', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
    renderAt('/projects', <App />)
    expect(await screen.findByText('no disponible')).toBeInTheDocument()
  })

  it('ofrece la navegación global y marca la sección actual', async () => {
    stubApi({ '/api/runners': runners })
    renderAt('/runners', <App />)
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' })
    for (const name of [
      'Dashboard',
      'Proyectos',
      'Workflows',
      'Ejecuciones',
      'Runners',
      'Actividad',
    ]) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument()
    }
    expect(within(nav).getByRole('link', { name: 'Runners' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByRole('link', { name: 'Saltar al contenido' })).toHaveAttribute(
      'href',
      '#main',
    )
  })

  it('el dashboard lista solo las excepciones, enlazadas', async () => {
    stubApi({ '/api/dashboard': dashboardSummary })
    renderAt('/', <App />)
    expect(
      await screen.findByRole('heading', { name: 'Agentes sin actividad (1)' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Runners sin latido (1)' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Ejecuciones activas (1)' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver todas las activas' })).toHaveAttribute(
      'href',
      '/runs?status=PENDING&status=RUNNING',
    )
    expect(screen.queryByRole('heading', { name: /Fallidas/ })).not.toBeInTheDocument()
  })

  it('el dashboard sin excepciones lo dice', async () => {
    stubApi({
      '/api/dashboard': {
        activeRuns: [],
        activeRunsTotal: 0,
        recentFailures: [],
        unresponsiveAgents: [],
        staleRunners: [],
        generatedAt: '2026-10-05T09:10:00Z',
      },
    })
    renderAt('/', <App />)
    expect(await screen.findByText('Nada requiere atención ahora mismo.')).toBeInTheDocument()
  })

  it('la lista de ejecuciones lee el filtro de la URL', async () => {
    const fetch = stubApi({
      '/api/workflow-runs?status=FAILED&page=0&size=25': { ...runPage, items: [] },
    })
    renderAt('/runs?status=FAILED', <App />)
    expect(await screen.findByText('No hay ejecuciones.')).toBeInTheDocument()
    expect(screen.getByLabelText('Estado')).toHaveDisplayValue('Fallidas')
    expect(fetch).toHaveBeenCalledWith(
      '/api/workflow-runs?status=FAILED&page=0&size=25',
      expect.anything(),
    )
  })

  it('la lista de ejecuciones muestra trabajo, estado, agente y herramienta', async () => {
    stubApi({ '/api/workflow-runs?page=0&size=25': runPage })
    renderAt('/runs', <App />)
    const row = (await screen.findByRole('link', { name: /TKM-1/ })).closest('tr')!
    expect(within(row).getByText('En curso')).toBeInTheDocument()
    expect(within(row).getByText('Ejecutando')).toBeInTheDocument()
    expect(within(row).getByText('Read')).toBeInTheDocument()
  })

  it('la página de runners distingue los que no envían latidos', async () => {
    stubApi({ '/api/runners': runners })
    renderAt('/runners', <App />)
    const stale = (await screen.findByText('runner-02')).closest('tr')!
    expect(within(stale).getByText('Sin latido')).toBeInTheDocument()
    expect(
      within(screen.getByText('runner-01').closest('tr')!).getByText('1 / 2'),
    ).toBeInTheDocument()
  })

  it('la cabecera de una ejecución en curso muestra estado, runner, tokens y conexión', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    stubApi({
      [`/api/workflow-runs/${runningRun.id}`]: runningRun,
      [`/api/agent-runs/${agentId}`]: {
        agent: runningRun.stages[0].agents[0],
        workflowRunId: runningRun.id,
        prompts: [],
      },
      '/api/runners': runners,
      [`POST /api/agent-runs/${agentId}/cancel`]: {
        agent: {},
        workflowRunId: runningRun.id,
        prompts: [],
      },
    })
    renderAt(`/runs/${runningRun.id}`, <App />)

    expect(
      await screen.findByRole('heading', { level: 1, name: /Add model pricing importer/ }),
    ).toBeInTheDocument()
    expect(await screen.findByText('runner-01')).toBeInTheDocument()
    expect(screen.getByText('4 entrada · 32 salida', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('caché: 28.437 leídos · 4256 escritos')).toBeInTheDocument()
    expect(screen.getByText('(llega al terminar)')).toBeInTheDocument()
    expect(screen.getByText('Conectando…')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar agente' }))
    expect(await screen.findByText(/Cancelación solicitada/)).toBeInTheDocument()
  })

  it('una ejecución terminada muestra el coste y no ofrece cancelar', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    stubApi({
      [`/api/workflow-runs/${runView.id}`]: runView,
      [`/api/agent-runs/${agentId}`]: {
        agent: runView.stages[0].agents[0],
        workflowRunId: runView.id,
        prompts: [],
      },
      '/api/runners': runners,
    })
    renderAt(`/runs/${runView.id}`, <App />)
    expect((await screen.findAllByText('Completada')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('0,0574 US$').length).toBeGreaterThan(0)
    expect(screen.getByText('42 s')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agente' })).not.toBeInTheDocument()
  })

  it('el formulario de lanzar envía solo los límites rellenados', async () => {
    // Tras lanzar, navega a la ejecución, que abre el stream de eventos.
    vi.stubGlobal('EventSource', FakeEventSource)
    const fetch = stubApi({
      '/api/work-items/w1': {
        id: 'w1',
        projectId: 'p1',
        key: 'TKM-1',
        title: 'T',
        description: null,
        type: 'BUG',
        externalRef: null,
        status: 'OPEN',
        createdAt: '',
      },
      '/api/projects/p1/repositories': [
        {
          id: 'repo1',
          projectId: 'p1',
          name: 'demo',
          localPath: '/r',
          remoteUrl: null,
          defaultBranch: 'main',
          createdAt: '',
        },
      ],
      'GET /api/work-items/w1/runs': [],
      'POST /api/work-items/w1/runs': runningRun,
    })
    renderAt('/work-items/w1', <App />)
    fireEvent.change(await screen.findByLabelText('Prompt'), { target: { value: 'Haz X' } })
    fireEvent.change(screen.getByLabelText('Turnos máximos'), { target: { value: '7' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lanzar' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Lanzar' }))

    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        '/api/work-items/w1/runs',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    const call = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(call[1]!.body as string)).toEqual({
      repositoryId: 'repo1',
      prompt: 'Haz X',
      maxTurns: 7,
    })
  })

  it('el selector de tema fija data-theme en el documento', async () => {
    stubApi({ '/api/runners': runners })
    renderAt('/runners', <App />)
    fireEvent.change(screen.getByLabelText('Tema'), { target: { value: 'dark' } })
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    fireEvent.change(screen.getByLabelText('Tema'), { target: { value: 'system' } })
    expect(document.documentElement).not.toHaveAttribute('data-theme')
  })
})
