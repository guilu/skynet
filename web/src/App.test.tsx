import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import agentRunDetail from '../../fixtures/contracts/agent-run-detail.json'
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

    fireEvent.click(header.getByRole('button', { name: 'Cancelar agente' }))
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
    fireEvent.change(screen.getByLabelText('Tema'), { target: { value: 'dark' } })
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

    function openRun(query = '') {
      vi.stubGlobal('EventSource', FakeEventSource)
      stubApi({
        [`/api/workflow-runs/${runId}`]: runView,
        [`/api/agent-runs/${agent.id}`]: agentRunDetail,
        '/api/runners': runners,
        [`/api/events/${toolCompleted.sequence}`]: toolCompleted,
        [`/api/events/${toolStarted.sequence}`]: toolStarted,
      })
      renderAt(`/runs/${runId}${query}`, <App />)
    }

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
      openRun('?tab=messages')
      await screen.findByRole('tab', { name: 'Mensajes' })
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
