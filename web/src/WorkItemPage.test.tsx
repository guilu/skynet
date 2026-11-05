import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import approval from '../../fixtures/contracts/approval.json'
import approvals from '../../fixtures/contracts/approvals.json'
import runView from '../../fixtures/contracts/run-view.json'
import summaries from '../../fixtures/contracts/work-item-summaries.json'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const id = runView.workItemId
const item = {
  id,
  projectId: 'p1',
  key: 'TKM-1',
  title: 'Add model pricing importer',
  description: 'Importar los precios de los modelos.',
  type: 'FEATURE',
  externalRef: null,
  status: 'OPEN',
  repositoryId: null,
  phase: 'WAITING',
  createdAt: '2026-10-05T08:00:00Z',
  archivedAt: null,
}
// El primer intento falló en la fase «agent»; el segundo espera una aprobación.
const first = {
  ...runView,
  id: 'run-1',
  status: 'FAILED',
  createdAt: '2026-10-05T08:00:00Z',
  totals: { ...runView.totals, costUsd: 1.12 },
  stages: runView.stages.map((s) => ({ ...s, name: 'Verificación', status: 'FAILED' })),
}
const second = {
  ...runView,
  id: 'run-2',
  status: 'RUNNING',
  finishedAt: null,
  totals: { ...runView.totals, costUsd: 0.41 },
  stages: runView.stages.map((s) => ({ ...s, name: 'Plan', status: 'WAITING_FOR_APPROVAL' })),
}

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      mockFetch({
        '/actuator/health': { status: 'UP' },
        '/api/auth/session': { username: 'admin' },
        [`/api/work-items/${id}`]: item,
        [`/api/work-items/${id}/summary`]: { ...summaries[0], id, costUsd: 1.53 },
        [`/api/work-items/${id}/runs`]: [second, first],
        '/api/projects/p1': { id: 'p1', key: 'TKM', name: 'TokenMeter', archivedAt: null },
        '/api/projects/p1/repositories': [],
        '/api/approvals?status=PENDING': approvals,
        [`/api/approvals/${approvals[0].id}`]: approval,
        [`/api/workflow-runs/run-2`]: second,
      }),
    ),
  )
}

describe('Página del trabajo (FS-E)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('cuenta qué, cómo e intentos, con el resultado y lo que ha costado', async () => {
    stubApi()
    renderAt(`/work-items/${id}`, <App />)
    expect(await screen.findByRole('heading', { level: 1, name: /TKM-1 Add model pricing/ }))
    expect(await screen.findByText('Te necesita')).toBeVisible()
    expect(
      screen.getByRole('heading', { name: 'Qué: el trabajo' }).nextElementSibling,
    ).toHaveTextContent('Importar los precios de los modelos.')
    expect(
      await screen.findByText('2 intentos. El actual espera tu aprobación.'),
    ).toBeInTheDocument()
    const list = screen.getByRole('list', { name: 'Intentos del trabajo' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent(/Intento 2.*actual.*Esperando aprobación.*0,41 US\$/)
    expect(rows[1]).toHaveTextContent(/Intento 1.*Fallido en Verificación.*1,12 US\$/)
    expect(within(rows[1]).getByRole('link', { name: 'Intento 1' })).toHaveAttribute(
      'href',
      '/runs/run-1',
    )
    const flow = screen.getByRole('list', { name: 'Fases del intento actual' })
    expect(flow).toHaveTextContent('Plan: Esperando aprobación')
    const result = screen.getByRole('region', { name: 'Resultado' })
    expect(within(result).getByRole('link', { name: 'PR #44' })).toBeVisible()
    const cost = screen.getByRole('region', { name: 'Lo que ha costado' })
    expect(cost).toHaveTextContent('1,53 US$')
    expect(cost).toHaveTextContent('1,12 US$ en intentos sin resultado')
  })

  it('revisa la aprobación en una hoja, sin salir del trabajo', async () => {
    stubApi()
    renderAt(`/work-items/${id}`, <App />)
    const buttons = await screen.findAllByRole('button', { name: 'Revisar la aprobación' })
    fireEvent.click(buttons[0])
    const sheet = await screen.findByRole('dialog', { name: 'Revisar la aprobación' })
    expect(await within(sheet).findByText('Pueden arrancar: implement.')).toBeVisible()
    expect(within(sheet).getByText('Este intento').nextElementSibling).toHaveTextContent('0,42 US$')
    expect(
      within(sheet).getByText('El trabajo, con todos sus intentos').nextElementSibling,
    ).toHaveTextContent('1,53 US$')
    expect(within(sheet).getByRole('button', { name: /Aprobar/ })).toBeVisible()
    expect(within(sheet).getByRole('link', { name: 'Ver toda la evidencia' })).toHaveAttribute(
      'href',
      `/approvals/${approval.id}`,
    )
  })

  it('la ejecución se presenta como el intento de su trabajo', async () => {
    stubApi()
    vi.stubGlobal(
      'EventSource',
      class {
        onopen = null
        onerror = null
        onmessage = null
        close() {}
      },
    )
    renderAt('/runs/run-2', <App />)
    expect(await screen.findByText('Intento 2 de')).toBeInTheDocument()
    const crumbs = screen.getByRole('navigation', { name: 'Migas' })
    expect(within(crumbs).getByText('Intento 2')).toHaveAttribute('aria-current', 'page')
    expect(within(crumbs).getByRole('link', { name: 'TKM-1' })).toHaveAttribute(
      'href',
      `/work-items/${id}`,
    )
  })
})
