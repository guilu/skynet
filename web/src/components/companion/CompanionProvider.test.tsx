import * as Tooltip from '@radix-ui/react-tooltip'
import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import runPage from '../../../../fixtures/contracts/run-page.json'
import runView from '../../../../fixtures/contracts/run-view.json'
import type { StoredEvent } from '../../api'
import { mockFetch, renderAt } from '../../test/render'
import { CompanionMenu } from './CompanionMenu'
import { CompanionProvider } from './CompanionProvider'
import { NoticeBell } from './NoticeBell'
import { resetCompanionPrefsCache, setCompanionPrefs } from './prefs'
import { Toasts } from './Toasts'

class FakeEventSource {
  static global: FakeEventSource | null = null
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((m: MessageEvent) => void) | null = null
  readonly url: string
  constructor(url: string) {
    this.url = url
    FakeEventSource.global = this
  }
  close() {}
  emit(...events: StoredEvent[]) {
    act(() => {
      for (const e of events) this.onmessage?.({ data: JSON.stringify(e) } as MessageEvent)
    })
  }
}

const RUN = runView.id
let sequence = 0
function event(type: string, payload: Record<string, unknown>): StoredEvent {
  sequence += 1
  return {
    sequence,
    eventId: `e${sequence}`,
    workflowRunId: RUN,
    aggregateType: type.startsWith('approval') ? 'approval_request' : 'workflow_run',
    aggregateId: RUN,
    type,
    payload,
    occurredAt: new Date().toISOString(),
    recordedAt: new Date().toISOString(),
  }
}

function Harness() {
  return (
    <Tooltip.Provider>
      <CompanionProvider>
        <CompanionMenu onOpenNotices={() => undefined} />
        <NoticeBell open={false} onOpenChange={() => undefined} />
        <Toasts />
      </CompanionProvider>
    </Tooltip.Provider>
  )
}

function start(path = '/') {
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.stubGlobal(
    'fetch',
    vi.fn(
      mockFetch({
        '/api/workflow-runs?status=PENDING&status=RUNNING&size=50': runPage,
        '/api/approvals?status=PENDING': [],
        [`/api/workflow-runs/${RUN}`]: runView,
      }),
    ),
  )
  renderAt(path, <Harness />)
}

beforeEach(() => resetCompanionPrefsCache())
afterEach(() => vi.unstubAllGlobals())

describe('companion global', () => {
  it('abre un único stream con solo lo nuevo y los tipos que importan', () => {
    start()
    const url = new URL(FakeEventSource.global!.url, 'http://x')
    expect(url.pathname).toBe('/api/events/stream')
    expect(url.searchParams.get('after')).toBe('latest')
    expect(url.searchParams.get('types')).toContain('approval.*')
  })

  it('la mascota de la barra dice cuántas ejecuciones hay en marcha', async () => {
    start()
    expect(
      await screen.findByRole('button', { name: /^Skynet: .*1 ejecución activa/ }),
    ).toBeInTheDocument()
  })

  it('una ejecución que termina bien avisa con su clave y queda en la campana', async () => {
    start()
    await screen.findByRole('button', { name: /1 ejecución activa/ })
    FakeEventSource.global!.emit(event('workflow.status.changed', { status: 'SUCCEEDED' }))
    expect(await screen.findByText('Workflow completado')).toBeInTheDocument()
    expect(screen.getByText(/^TKM-1/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Avisos: 1 sin leer' })).toBeInTheDocument()
  })

  it('dos fallos seguidos van en una sola tarjeta', async () => {
    start()
    FakeEventSource.global!.emit(
      event('workflow.status.changed', { status: 'FAILED' }),
      event('workflow.status.changed', { status: 'FAILED' }),
    )
    expect(await screen.findByText('2 ejecuciones han fallado')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver todas' })).toHaveAttribute(
      'href',
      '/runs?status=FAILED',
    )
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^Skynet: Error/ })).toBeInTheDocument(),
    )
  })

  it('no avisa de la ejecución que estás mirando, salvo si te necesita', async () => {
    start(`/runs/${RUN}`)
    FakeEventSource.global!.emit(
      event('workflow.status.changed', { status: 'SUCCEEDED' }),
      event('approval.requested', { approvalId: 'a1', stageKey: 'aprobar' }),
    )
    expect(await screen.findByText('Te necesito')).toBeInTheDocument()
    expect(screen.queryByText('Workflow completado')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Avisos: 1 sin leer' })).toBeInTheDocument()
  })

  it('el aviso de una aprobación se va al decidirla', async () => {
    start()
    FakeEventSource.global!.emit(event('approval.requested', { approvalId: 'a1', stageKey: 'x' }))
    expect(await screen.findByRole('link', { name: 'Revisar' })).toHaveAttribute(
      'href',
      '/approvals/a1',
    )
    FakeEventSource.global!.emit(event('approval.granted', { approvalId: 'a1' }))
    await waitFor(() => expect(screen.queryByText('Te necesito')).not.toBeInTheDocument())
  })

  it('silenciada, no salen tarjetas pero la campana cuenta', async () => {
    setCompanionPrefs({ mutedUntil: Date.now() + 60_000 })
    start()
    FakeEventSource.global!.emit(event('approval.requested', { approvalId: 'a1', stageKey: 'x' }))
    expect(await screen.findByRole('button', { name: 'Avisos: 1 sin leer' })).toBeInTheDocument()
    expect(screen.queryByText('Te necesito')).not.toBeInTheDocument()
  })

  it('el aviso se cierra con su botón', async () => {
    start()
    FakeEventSource.global!.emit(event('approval.requested', { approvalId: 'a1', stageKey: 'x' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Cerrar el aviso' }))
    expect(screen.queryByText('Te necesito')).not.toBeInTheDocument()
  })
})
