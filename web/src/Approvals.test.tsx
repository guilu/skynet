import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import approval from '../../fixtures/contracts/approval.json'
import approvals from '../../fixtures/contracts/approvals.json'
import App from './App'
import { mockFetch, renderAt } from './test/render'

function stubApi(routes: Record<string, unknown> = {}) {
  const fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/approvals?': approvals,
      '/api/approvals?status=PENDING': approvals,
      [`/api/approvals/${approval.id}`]: approval,
      [`POST /api/approvals/${approval.id}/decisions`]: { ...approval, status: 'APPROVED' },
      ...routes,
    })(input, init),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

/** Lo que se envió en cada decisión. */
const decisions = (fetch: ReturnType<typeof vi.fn>) =>
  fetch.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === 'POST')
    .map(([, init]) => JSON.parse(String((init as RequestInit).body)))

describe('Aprobaciones', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('el centro lista las peticiones y el menú cuenta las pendientes', async () => {
    stubApi()
    renderAt('/approvals', <App />)

    const table = await screen.findByRole('table', { name: 'Aprobaciones' })
    const row = within(table).getAllByRole('row')[1]
    expect(within(row).getByRole('link', { name: 'Aprobar el plan de TKM-1' })).toHaveAttribute(
      'href',
      `/approvals/${approval.id}`,
    )
    expect(row).toHaveTextContent('Riesgo alto')
    expect(row).toHaveTextContent('Pendiente')
    expect(row).toHaveTextContent('TKM-1 · Add model pricing importer')
    const nav = screen.getByRole('navigation', { name: 'Navegación principal' })
    expect(
      await within(nav).findByRole('link', { name: /^Aprobaciones,\s*1\s*pendiente$/ }),
    ).toBeInTheDocument()
  })

  it('una petición enseña su evidencia y sus consecuencias', async () => {
    stubApi()
    renderAt(`/approvals/${approval.id}`, <App />)

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Aprobar el plan de TKM-1' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'TKM-1 · Add model pricing importer' })).toBeVisible()
    const evidence = screen.getByRole('region', { name: 'Lo que dejaron sus dependencias' })
    expect(evidence).toHaveTextContent('Importar los precios desde un CSV')
    expect(evidence).toHaveTextContent('Rama skynet/tkm-1-plan · 1 commit · 2 ficheros +48 −3')
    expect(within(evidence).getByRole('list', { name: 'Comprobaciones' })).toHaveTextContent(
      'artifacts openspec/tasks.md: pasa',
    )
    const consequences = screen.getByRole('region', { name: 'Qué pasa con cada decisión' })
    expect(consequences).toHaveTextContent('Pueden arrancar: implement.')
    expect(screen.getByRole('region', { name: 'Historial' })).toHaveTextContent(
      '¿Cubre los precios en euros?',
    )
  })

  it('rechazar y pedir cambios exigen un comentario', async () => {
    const fetch = stubApi()
    renderAt(`/approvals/${approval.id}`, <App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Rechazar' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Para rechazar, escribe el motivo.')
    fireEvent.click(screen.getByRole('button', { name: 'Pedir cambios' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Para pedir cambios, escribe qué cambiar.')
    expect(decisions(fetch)).toEqual([])

    fireEvent.change(screen.getByLabelText(/Comentario/), {
      target: { value: 'Cubre también los euros' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Pedir cambios' }))
    await waitFor(() =>
      expect(decisions(fetch)).toEqual([
        { action: 'REQUEST_CHANGES', comment: 'Cubre también los euros' },
      ]),
    )
  })

  it('aprobar algo de riesgo alto pide confirmarlo', async () => {
    const fetch = stubApi()
    renderAt(`/approvals/${approval.id}`, <App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Aprobar' }))
    const confirm = await screen.findByRole('dialog', { name: 'Aprobar con riesgo alto' })
    expect(confirm).toHaveTextContent('Pueden arrancar: implement.')
    expect(decisions(fetch)).toEqual([])
    fireEvent.click(within(confirm).getByRole('button', { name: 'Sí, aprobar' }))
    await waitFor(() => expect(decisions(fetch)).toEqual([{ action: 'APPROVE' }]))
  })
})
