import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import agentRunDetail from '../../fixtures/contracts/agent-run-detail.json'
import conversation from '../../fixtures/contracts/conversation.json'
import dashboardMetrics from '../../fixtures/contracts/dashboard-metrics.json'
import dashboardSummary from '../../fixtures/contracts/dashboard-summary.json'
import runPage from '../../fixtures/contracts/run-page.json'
import runView from '../../fixtures/contracts/run-view.json'
import runners from '../../fixtures/contracts/runners.json'
import storedEvent from '../../fixtures/contracts/stored-event.json'
import type { StoredEvent } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

class FakeEventSource {
  static last: FakeEventSource | null = null
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((m: MessageEvent) => void) | null = null
  constructor() {
    FakeEventSource.last = this
  }
  close() {}
  /** Entrega eventos como si llegaran por el stream. */
  emit(...events: StoredEvent[]) {
    act(() => {
      for (const e of events) this.onmessage?.({ data: JSON.stringify(e) } as MessageEvent)
    })
  }
}

// Las vistas de ejemplo son las mismas que valida ContractIT en el backend.
const runningRun = runPage.items[0]
const agentId = runningRun.stages[0].agents[0].id

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      ...routes,
    }),
  )
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
    const api = mockFetch({ '/api/auth/session': { username: 'admin' }, '/api/projects': [] })
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
        String(input) === '/actuator/health'
          ? Promise.reject(new TypeError('network'))
          : api(input, init),
      ),
    )
    renderAt('/projects', <App />)
    expect(await screen.findByText('no disponible')).toBeInTheDocument()
  })

  it('ofrece la navegación global y marca la sección actual', async () => {
    stubApi({ '/api/runners': runners })
    renderAt('/runners', <App />)
    const nav = await screen.findByRole('navigation', { name: 'Navegación principal' })
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
    const stuck = dashboardSummary.unresponsiveAgents[0]
    expect(screen.getByRole('link', { name: stuck.workItemKey! })).toHaveAttribute(
      'href',
      `/runs/${stuck.workflowRunId}?agent=${stuck.agentRunId}`,
    )
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

  it('las métricas del periodo enlazan a la lista filtrada y cambian de periodo', async () => {
    const tz = encodeURIComponent(Intl.DateTimeFormat().resolvedOptions().timeZone)
    const fetch = stubApi({
      '/api/dashboard': dashboardSummary,
      [`/api/dashboard/metrics?period=7d&tz=${tz}`]: { ...dashboardMetrics, bucket: 'day' },
      [`/api/dashboard/metrics?period=24h&tz=${tz}`]: dashboardMetrics,
    })
    renderAt('/', <App />)
    const metrics = await screen.findByRole('region', { name: 'Métricas' })
    await within(metrics).findByText('Fallidas', { selector: 'dt' })
    expect(
      within(metrics)
        .getByText('Fallidas', { selector: 'dt' })
        .nextElementSibling?.querySelector('a'),
    ).toHaveAttribute(
      'href',
      `/runs?status=FAILED&since=${encodeURIComponent(dashboardMetrics.since)}`,
    )
    expect(
      within(metrics).getByText('Coste', { selector: 'dt' }).nextElementSibling,
    ).toHaveTextContent('0,1234 US$')
    expect(within(metrics).getByText('Duración mediana').nextElementSibling).toHaveTextContent(
      '1 min 35 s',
    )
    expect(
      within(metrics).getByRole('img', { name: /Ejecuciones por día: 3 en total/ }),
    ).toBeInTheDocument()
    // La misma información, en tabla.
    expect(within(metrics).getAllByRole('row')).toHaveLength(3)

    fireEvent.click(within(metrics).getByRole('button', { name: '24 horas' }))
    expect(
      await within(metrics).findByRole('img', { name: /Ejecuciones por hora/ }),
    ).toBeInTheDocument()
    expect(within(metrics).getByRole('button', { name: '24 horas' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(fetch).toHaveBeenCalledWith(
      `/api/dashboard/metrics?period=24h&tz=${tz}`,
      expect.anything(),
    )
  })

  it('la lista de ejecuciones filtra por fecha de creación y se puede quitar', async () => {
    const since = '2026-10-04T09:00:00Z'
    const fetch = stubApi({
      [`/api/workflow-runs?status=FAILED&since=${encodeURIComponent(since)}&page=0&size=25`]: {
        ...runPage,
        items: [],
      },
      '/api/workflow-runs?status=FAILED&page=0&size=25': runPage,
    })
    renderAt(`/runs?status=FAILED&since=${since}`, <App />)
    expect(await screen.findByText(/Creadas desde/)).toBeInTheDocument()
    expect(await screen.findByText('No hay ejecuciones.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Quitar' }))
    await waitFor(() => expect(screen.queryByText(/Creadas desde/)).toBeNull())
    expect(fetch).toHaveBeenCalledWith(
      '/api/workflow-runs?status=FAILED&page=0&size=25',
      expect.anything(),
    )
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

    const title = await screen.findByRole('heading', {
      level: 1,
      name: /Add model pricing importer/,
    })
    const header = within(title.closest('header')!)
    expect(await header.findByText('runner-01')).toBeInTheDocument()
    expect(header.getByText('4 entrada · 32 salida', { exact: false })).toBeInTheDocument()
    expect(header.getByText('caché: 28.437 leídos · 4256 escritos')).toBeInTheDocument()
    expect(header.getByText('(llega al terminar)')).toBeInTheDocument()
    expect(header.getByText('Conectando…')).toBeInTheDocument()

    // Interrumpir pide una confirmación sencilla.
    fireEvent.click(header.getByRole('button', { name: 'Cancelar agente' }))
    expect(header.getByText(/¿Cancelar el agente\?/)).toBeInTheDocument()
    fireEvent.click(header.getByRole('button', { name: 'No' }))
    fireEvent.click(header.getByRole('button', { name: 'Cancelar agente' }))
    fireEvent.click(header.getByRole('button', { name: 'Sí, cancelar' }))
    expect(await header.findByText(/Cancelación solicitada/)).toBeInTheDocument()
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
          validationCommand: null,
          testReportPaths: [],
          agentPolicy: {
            allowedTools: ['Read', 'Bash(git:*)'],
            permissionMode: 'dontAsk',
            environment: null,
            maxTurns: null,
            maxBudgetUsd: 1.5,
            timeoutMinutes: 10,
          },
          agentPolicyCustom: true,
          createdAt: '',
        },
      ],
      'GET /api/work-items/w1/runs': [],
      'POST /api/work-items/w1/runs': runningRun,
      [`/api/workflow-runs/${runningRun.id}`]: runningRun,
    })
    renderAt('/work-items/w1', <App />)
    fireEvent.change(await screen.findByLabelText('Prompt'), { target: { value: 'Haz X' } })
    fireEvent.change(screen.getByLabelText('Turnos máximos'), { target: { value: '7' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lanzar' })).toBeEnabled())
    // Se ve la política del repositorio y sus máximos.
    expect(
      screen.getByText(
        (_, el) => el?.tagName === 'P' && !!el.textContent?.startsWith('Política del repositorio:'),
      ),
    ).toHaveTextContent(
      'Política del repositorio: herramientas Read, Bash(git:*), modo dontAsk, entorno el que' +
        ' permite el runner.',
    )
    expect(screen.getByLabelText('Presupuesto (US$) (hasta 1.5)')).toHaveAttribute('max', '1.5')
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
    // Termina en la página de la ejecución lanzada.
    expect(
      await screen.findByRole('heading', { level: 1, name: /Add model pricing importer/ }),
    ).toBeInTheDocument()
  })

  it('el selector de tema fija data-theme en el documento', async () => {
    stubApi({ '/api/runners': runners })
    renderAt('/runners', <App />)
    fireEvent.change(await screen.findByLabelText('Tema'), { target: { value: 'dark' } })
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    fireEvent.change(screen.getByLabelText('Tema'), { target: { value: 'system' } })
    expect(document.documentElement).not.toHaveAttribute('data-theme')
  })
  describe('inspector de la ejecución', () => {
    const runId = runView.id
    const agent = runView.stages[0].agents[0]
    const toolStarted = storedEvent as StoredEvent
    const at = (sequence: number, type: string, payload: Record<string, unknown>) => ({
      ...toolStarted,
      sequence,
      eventId: `e${sequence}`,
      type,
      payload,
    })
    const toolCompleted = at(1043, 'agent.tool.completed', {
      toolUseId: 'toolu_01A',
      name: 'Read',
      isError: false,
      output: 'x'.repeat(2500),
    })
    const message = at(1044, 'agent.message.received', { text: '<img src=x onerror=alert(1)>' })

    function openRun(query = '', routes: Record<string, unknown> = {}) {
      vi.stubGlobal('EventSource', FakeEventSource)
      const fetch = stubApi({
        [`/api/workflow-runs/${runId}`]: runView,
        [`/api/agent-runs/${agent.id}`]: agentRunDetail,
        '/api/runners': runners,
        [`/api/events/${toolCompleted.sequence}`]: toolCompleted,
        [`/api/events/${toolStarted.sequence}`]: toolStarted,
        ...routes,
      })
      renderAt(`/runs/${runId}${query}`, <App />)
      return fetch
    }

    /** Llamadas a la API con ese método y ruta, con su cuerpo. */
    function calls(fetch: ReturnType<typeof vi.fn>, method: string, path: string) {
      return fetch.mock.calls
        .filter(([url, init]) => String(url).endsWith(path) && init?.method === method)
        .map(([, init]) => (init?.body ? JSON.parse(String(init.body)) : null))
    }

    const resumed = conversation.turns[1].agent

    it('la conversación encadena las invocaciones y continúa desde la última', async () => {
      const fetch = openRun('?tab=conversation', {
        [`/api/agent-runs/${agent.id}/conversation`]: conversation,
        [`POST /api/agent-runs/${resumed.id}/messages`]: { ...runView, id: 'nueva' },
        '/api/workflow-runs/nueva': { ...runView, id: 'nueva' },
      })
      const panel = await screen.findByRole('tabpanel')
      expect(
        await within(panel).findByText('Implementa el importador de precios de modelos'),
      ).toBeInTheDocument()
      expect(
        within(panel).getByText('He añadido `PricingImporter` y sus tests.'),
      ).toBeInTheDocument()
      expect(within(panel).getByText('Reanudación')).toBeInTheDocument()
      expect(within(panel).getByRole('link', { name: 'Ver invocación' })).toHaveAttribute(
        'href',
        `/runs/${conversation.turns[1].workflowRunId}?agent=${resumed.id}&tab=conversation`,
      )

      // Continúa desde la última invocación de la sesión, y dice dónde.
      const composer = within(panel).getByRole('form', { name: 'Continuar la conversación' })
      expect(within(composer).getByText(resumed.workspace!.path)).toBeInTheDocument()
      expect(await within(composer).findByText(/runner-01/)).toBeInTheDocument()
      fireEvent.change(within(composer).getByLabelText('Mensaje'), {
        target: { value: 'Añade un test' },
      })
      fireEvent.click(within(composer).getByRole('button', { name: 'Enviar' }))
      await waitFor(() =>
        expect(calls(fetch, 'POST', `/api/agent-runs/${resumed.id}/messages`)).toEqual([
          { text: 'Añade un test' },
        ]),
      )
      // Y lleva a la ejecución nueva.
      await waitFor(() =>
        expect(calls(fetch, 'GET', '/api/workflow-runs/nueva').length).toBeGreaterThan(0),
      )
    })

    it('la caja de mensaje espera a que termine la invocación en curso', async () => {
      const running = { ...resumed, status: 'THINKING', finishedAt: null }
      openRun('?tab=conversation', {
        [`/api/agent-runs/${agent.id}/conversation`]: {
          turns: [conversation.turns[0], { ...conversation.turns[1], agent: running }],
        },
      })
      expect(
        await screen.findByText('Podrás escribir cuando termine la invocación en curso.'),
      ).toBeInTheDocument()
      expect(screen.getByLabelText('Mensaje')).toBeDisabled()
    })

    it('reintentar y bifurcar explican su alcance antes de confirmar', async () => {
      const fetch = openRun('', {
        [`POST /api/agent-runs/${agent.id}/retry`]: { ...runView, id: 'reintento' },
        [`POST /api/agent-runs/${agent.id}/fork`]: { ...runView, id: 'fork' },
        // Cada acción lleva a la ejecución nueva.
        '/api/workflow-runs/reintento': { ...runView, id: 'reintento' },
        '/api/workflow-runs/fork': { ...runView, id: 'fork' },
      })
      fireEvent.click(await screen.findByRole('button', { name: 'Reintentar…' }))
      expect(screen.getByText(/mismo prompt y los mismos límites/)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      await waitFor(() =>
        expect(calls(fetch, 'POST', `/api/agent-runs/${agent.id}/retry`)).toHaveLength(1),
      )
      // La ejecución nueva se abre en la misma página, que se vuelve a pintar sin el panel.
      await waitFor(() => expect(screen.queryByText('Reintentar el agente')).toBeNull())

      fireEvent.click(await screen.findByRole('button', { name: 'Bifurcar…' }))
      expect(screen.getByText(/cambios sin confirmar incluidos/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Bifurcar' })).toBeDisabled()
      fireEvent.change(screen.getByLabelText('Mensaje para el fork'), {
        target: { value: 'Prueba otra cosa' },
      })
      fireEvent.click(screen.getByRole('button', { name: 'Bifurcar' }))
      await waitFor(() =>
        expect(calls(fetch, 'POST', `/api/agent-runs/${agent.id}/fork`)).toEqual([
          { text: 'Prueba otra cosa' },
        ]),
      )
      await waitFor(() =>
        expect(calls(fetch, 'GET', '/api/workflow-runs/fork').length).toBeGreaterThan(0),
      )
    })

    it('eliminar el worktree explica qué se pierde antes de confirmar', async () => {
      const fetch = openRun('', {
        [`POST /api/agent-runs/${agent.id}/workspace/cleanup`]: agentRunDetail,
      })
      fireEvent.click(await screen.findByRole('button', { name: 'Eliminar worktree…' }))
      expect(screen.getByText(/se conservan en el repositorio/)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Eliminar' }))
      await waitFor(() =>
        expect(calls(fetch, 'POST', `/api/agent-runs/${agent.id}/workspace/cleanup`)).toHaveLength(
          1,
        ),
      )
      await waitFor(() => expect(screen.queryByText('Eliminar el worktree')).toBeNull())
    })

    it('con el worktree eliminado no deja continuar, bifurcar ni verificar', async () => {
      const removed = {
        ...agent,
        workspace: { ...agent.workspace!, removedAt: '2026-10-07T10:00:00Z' },
      }
      openRun('?tab=conversation', {
        [`/api/workflow-runs/${runId}`]: {
          ...runView,
          stages: [{ ...runView.stages[0], agents: [removed] }],
        },
        [`/api/agent-runs/${agent.id}/conversation`]: {
          turns: [{ ...conversation.turns[0], agent: removed }],
        },
      })
      expect(await screen.findByText(/Worktree eliminado el/)).toBeInTheDocument()
      expect(
        await screen.findByText('El worktree de esta sesión se eliminó: no se puede continuar.'),
      ).toBeInTheDocument()
      expect(screen.getByLabelText('Mensaje')).toBeDisabled()
      expect(screen.queryByRole('button', { name: 'Bifurcar…' })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Eliminar worktree…' })).toBeNull()
    })

    it('una reanudación enlaza con el agente del que parte y no ofrece reintentar', async () => {
      const child = {
        ...agent,
        kind: 'RESUME',
        parentAgentRunId: 'a1b2c3d4-0000-4000-8000-000000000000',
      }
      openRun('', {
        [`/api/workflow-runs/${runId}`]: {
          ...runView,
          stages: [{ ...runView.stages[0], agents: [child] }],
        },
      })
      const nav = await screen.findByRole('navigation', { name: 'Fases y agentes' })
      expect(within(nav).getByText(/reanudación del/)).toBeInTheDocument()
      expect(within(nav).getByRole('link', { name: 'agente a1b2c3d4' })).toHaveAttribute(
        'href',
        '/agent-runs/a1b2c3d4-0000-4000-8000-000000000000',
      )
      expect(screen.getByRole('button', { name: 'Bifurcar…' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Reintentar…' })).toBeNull()
    })

    it('elige por defecto el agente de la cabecera y muestra su resumen', async () => {
      openRun()
      const nav = await screen.findByRole('navigation', { name: 'Fases y agentes' })
      expect(within(nav).getByRole('button', { name: /claude-code/ })).toHaveAttribute(
        'aria-current',
        'true',
      )
      expect(screen.getByRole('tab', { name: 'Resumen' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByText('claude-opus-5-5')).toBeInTheDocument()
    })

    it('abre la pestaña de la URL y empareja cada herramienta con su resultado', async () => {
      openRun(`?agent=${agent.id}&tab=tools`)
      expect(await screen.findByRole('tab', { name: 'Herramientas' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      FakeEventSource.last!.emit(toolStarted, toolCompleted)
      expect(await screen.findByText('Read')).toBeInTheDocument()
      expect(screen.getByText(': /w/README.md')).toBeInTheDocument()
      expect(screen.getByText('Hecha')).toBeInTheDocument()

      // La salida larga sale recortada hasta pedirla entera.
      expect(screen.getByText(/x{2000}…/)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /Ver completo/ }))
      expect(screen.getByText('x'.repeat(2500))).toBeInTheDocument()

      // El evento original se lee de la API.
      fireEvent.click(screen.getByRole('button', { name: 'Evento de resultado #1043' }))
      expect(screen.getByRole('tab', { name: 'Evento original' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      expect(await screen.findByText(/"type": "agent.tool.completed"/)).toBeInTheDocument()
    })

    it('pinta los mensajes del agente como texto, nunca como HTML', async () => {
      // ?tab=messages es el enlace antiguo a la pestaña, ahora Conversación.
      openRun('?tab=messages')
      expect(await screen.findByRole('tab', { name: 'Conversación' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      FakeEventSource.last!.emit(message)
      expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
      expect(document.querySelector('.run-inspector img')).toBeNull()
    })

    it('muestra el prompt con el que se lanzó el agente', async () => {
      openRun('?tab=prompt')
      expect(
        await screen.findByText('Implementa el importador de precios de modelos'),
      ).toBeInTheDocument()
    })

    it('las flechas recorren las pestañas', async () => {
      openRun()
      const summary = await screen.findByRole('tab', { name: 'Resumen' })
      fireEvent.keyDown(summary, { key: 'ArrowRight' })
      expect(screen.getByRole('tab', { name: 'Prompt' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByRole('tab', { name: 'Prompt' })).toHaveFocus()
      fireEvent.keyDown(screen.getByRole('tab', { name: 'Prompt' }), { key: 'End' })
      expect(screen.getByRole('tab', { name: 'Evento original' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      fireEvent.keyDown(screen.getByRole('tab', { name: 'Evento original' }), {
        key: 'ArrowRight',
      })
      expect(screen.getByRole('tab', { name: 'Resumen' })).toHaveAttribute('aria-selected', 'true')
    })

    it('elegir una entrada del timeline la abre y enseña sus eventos originales', async () => {
      openRun()
      await screen.findByRole('tab', { name: 'Resumen' })
      FakeEventSource.last!.emit(toolStarted, toolCompleted)
      const timeline = within(screen.getByRole('region', { name: 'Timeline' }))
      // Inicio y fin de la herramienta forman una sola entrada.
      const entry = timeline.getByRole('button', {
        name: /agent\.tool\.started.*Read: \/w\/README\.md/,
      })
      expect(timeline.queryByRole('button', { name: /#1043/ })).not.toBeInTheDocument()
      fireEvent.click(entry)
      expect(await screen.findByText(/"type": "agent.tool.started"/)).toBeInTheDocument()

      // La entrada elegida se abre: cada evento original se puede abrir por separado.
      expect(timeline.getByRole('button', { name: /Abrir|Cerrar/ })).toHaveAttribute(
        'aria-expanded',
        'true',
      )
      const completed = timeline.getByRole('button', { name: /#1043/ })
      fireEvent.click(completed)
      expect(completed).toHaveAttribute('aria-current', 'true')
      expect(await screen.findByText(/"type": "agent.tool.completed"/)).toBeInTheDocument()
    })

    it('el timeline se recorre con el teclado', async () => {
      openRun()
      await screen.findByRole('tab', { name: 'Resumen' })
      FakeEventSource.last!.emit(message, toolStarted, toolCompleted)
      const timeline = within(screen.getByRole('region', { name: 'Timeline' }))
      const first = timeline.getByRole('button', { name: /Mensaje: / })
      first.focus()
      fireEvent.keyDown(first, { key: 'ArrowDown' })
      const tool = timeline.getByRole('button', { name: /agent\.tool\.started.*Read: / })
      await waitFor(() => expect(tool).toHaveFocus())

      fireEvent.keyDown(tool, { key: 'ArrowRight' })
      expect(timeline.getByRole('button', { name: /Cerrar: Read/ })).toHaveAttribute(
        'aria-expanded',
        'true',
      )
      fireEvent.keyDown(tool, { key: 'ArrowLeft' })
      expect(timeline.queryByRole('button', { name: /#1043/ })).not.toBeInTheDocument()
    })

    it('los filtros salen de la URL y avisan si ocultan el evento elegido', async () => {
      openRun(`?tab=event&seq=${toolCompleted.sequence}&f.kind=message`)
      await screen.findByRole('tab', { name: 'Evento original' })
      FakeEventSource.last!.emit(toolStarted, toolCompleted, message)
      const timeline = within(screen.getByRole('region', { name: 'Timeline' }))
      expect(timeline.getByLabelText('Tipo')).toHaveDisplayValue('Mensajes')
      expect(timeline.getByRole('button', { name: /Mensaje: <img/ })).toBeInTheDocument()
      expect(timeline.queryByRole('button', { name: /Read: / })).not.toBeInTheDocument()
      expect(timeline.getByText(/no se ve con estos filtros/)).toBeInTheDocument()

      fireEvent.click(timeline.getByRole('button', { name: 'Quitar filtros' }))
      expect(timeline.getByLabelText('Tipo')).toHaveDisplayValue('Todos')
      expect(timeline.getByRole('button', { name: /#1043/ })).toHaveAttribute(
        'aria-current',
        'true',
      )
    })
  })

  it('aplica los eventos en vivo a la cabecera sin volver a pedir la ejecución', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const fetch = stubApi({
      [`/api/workflow-runs/${runningRun.id}`]: runningRun,
      [`/api/agent-runs/${agentId}`]: agentRunDetail,
      '/api/runners': runners,
    })
    renderAt(`/runs/${runningRun.id}`, <App />)
    const title = await screen.findByRole('heading', { level: 1, name: /Add model pricing/ })
    FakeEventSource.last!.emit({
      ...(storedEvent as StoredEvent),
      workflowRunId: runningRun.id,
      aggregateId: agentId,
      sequence: 2000,
      payload: { toolUseId: 'g', name: 'Grep', input: { pattern: 'price' } },
    })
    expect(await within(title.closest('header')!).findByText(/Grep/)).toBeInTheDocument()
    const runFetches = fetch.mock.calls.filter(
      ([url]) => url === `/api/workflow-runs/${runningRun.id}`,
    )
    expect(runFetches).toHaveLength(1)
  })

  it('el timeline solo pinta las filas visibles', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    stubApi({})
    renderAt('/activity', <App />)
    await screen.findByRole('heading', { name: /Actividad/ })
    FakeEventSource.last!.emit(
      ...Array.from({ length: 2000 }, (_, i) => ({
        ...(storedEvent as StoredEvent),
        sequence: i + 1,
        type: 'agent.message.received',
        payload: { text: `mensaje ${i + 1}` },
      })),
    )
    expect(await screen.findByText('Mensaje: mensaje 1')).toBeInTheDocument()
    expect(document.querySelectorAll('.timeline-item').length).toBeLessThan(100)
  })

  it('la actividad enlaza cada evento con el inspector de su ejecución', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    stubApi({
      [`/api/workflow-runs/${runView.id}`]: runView,
      [`/api/agent-runs/${runView.stages[0].agents[0].id}`]: agentRunDetail,
      '/api/runners': runners,
      [`/api/events/${storedEvent.sequence}`]: storedEvent,
    })
    renderAt('/activity', <App />)
    await screen.findByRole('heading', { name: /Actividad/ })
    FakeEventSource.last!.emit(storedEvent as StoredEvent)
    fireEvent.click(await screen.findByRole('button', { name: /Read: \/w\/README.md/ }))
    expect(await screen.findByRole('tab', { name: 'Evento original' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(await screen.findByText(/"sequence": 1042/)).toBeInTheDocument()
  })
})
