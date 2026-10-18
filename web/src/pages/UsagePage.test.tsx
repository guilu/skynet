import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import dashboardSummary from '../../../fixtures/contracts/dashboard-summary.json'
import runners from '../../../fixtures/contracts/runners.json'
import usageAgents from '../../../fixtures/contracts/usage-agents.json'
import usageHistory from '../../../fixtures/contracts/usage-history.json'
import usageRuns from '../../../fixtures/contracts/usage-runs.json'
import usageWindows from '../../../fixtures/contracts/usage-windows.json'
import App from '../App'
import { mockFetch, renderAt } from '../test/render'

// Las vistas de ejemplo son las que valida ContractIT en el backend.
const runner = usageWindows.runners[0].id

function stubApi(routes: Record<string, unknown> = {}) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/usage/windows': usageWindows,
      '/api/dashboard': dashboardSummary,
      '/api/runners': runners,
      [`/api/usage/history?provider=claude-code&bucket=default&window=five_hour&runner=${runner}`]:
        usageHistory,
      [`/api/usage/agents?period=7d&runner=${runner}`]: usageAgents,
      [`/api/usage/runs?period=7d&limit=5&runner=${runner}`]: usageRuns,
      [`POST /api/usage/refresh?runner=${runner}`]: { runners: 1 },
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

describe('página Uso', () => {
  beforeEach(() => {
    // Solo la fecha: las muestras de ejemplo son del 5 de octubre a las 9:00.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-05T09:05:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('enseña cada CLI con sus medidores, lo de Skynet y lo de fuera', async () => {
    stubApi()
    renderAt('/usage', <App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Uso de los CLIs' })).toBeVisible()
    expect(
      within(screen.getByRole('navigation', { name: 'Navegación principal' })).getByRole('link', {
        name: 'Uso',
      }),
    ).toHaveAttribute('aria-current', 'page')
    // La ventana más gastada del runner, también la de agy desde que lanza agentes (AR-B).
    expect(await screen.findByText(/^Antigravity CLI va al 40 % de su semana/)).toBeVisible()
    expect(screen.getByText('Runner: laptop')).toBeVisible()

    const claude = screen.getByRole('region', { name: 'Claude Code' })
    // El runner tiene Codex pero aún no hay ninguna muestra: la tarjeta sale igual, sin medidores.
    const codex = screen.getByRole('region', { name: 'Codex' })
    expect(within(codex).getByText('Sin datos')).toBeVisible()
    expect(within(codex).queryByRole('meter')).toBeNull()
    expect(within(claude).getByText('Disponible')).toBeVisible()
    const meter = within(claude).getByRole('meter', { name: 'Claude Code, ventana de 5 h' })
    expect(meter).toHaveAttribute('aria-valuenow', '16')
    expect(meter).toHaveAttribute('aria-valuetext', '16 % usado: Skynet 2 %, fuera 14 %')
    expect(within(claude).getByText('Skynet 2 %')).toBeVisible()
    expect(within(claude).getByText(/Se renueva a las \d\d:30 \(en 2 h 25 min\)/)).toBeVisible()
    expect(within(claude).getByText('Dato de hace 4 min, de la última ejecución')).toBeVisible()

    // agy se ve por grupos de modelos.
    const agy = screen.getByRole('region', { name: 'Antigravity CLI' })
    expect(within(agy).getByRole('heading', { name: 'Gemini Models' })).toBeVisible()
    expect(
      within(agy).getByRole('meter', { name: 'Antigravity CLI (Gemini Models), semana' }),
    ).toHaveAttribute('aria-valuenow', '40')
    expect(within(agy).queryByText('Solo lectura')).toBeNull()

    // El aviso del Dashboard también sale aquí.
    const attention = screen.getByRole('region', { name: 'Necesita tu atención (1)' })
    expect(
      within(attention).getByRole('link', { name: /Claude Code al 78 % de la semana/ }),
    ).toBeVisible()
  })

  it('lista lo que cuesta cada agente y las ejecuciones que más han gastado', async () => {
    stubApi()
    renderAt('/usage', <App />)
    const costs = await screen.findByRole('region', { name: 'Qué cuesta cada agente' })
    const row = await within(costs).findByRole('row', { name: /revisor/ })
    expect(row).toHaveTextContent('Revisar y corregir · Claude Code · 10,3 k tokens')
    expect(
      within(row)
        .getAllByRole('cell')
        .map((c) => c.textContent),
    ).toEqual([expect.stringContaining('revisor'), '4', '2,4 %', '0,6 %', '0,8 %'])
    const run = within(costs).getByRole('link', { name: /TKM-1 · Add model pricing importer/ })
    expect(run).toHaveAttribute('href', '/runs/0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a04')
    expect(run).toHaveTextContent('2 % de 5 h')
    expect(run).toHaveTextContent('Claude · 0,4 % semanal')
  })

  it('«Actualizar» pide a los runners que consulten sus cuotas', async () => {
    const fetch = stubApi()
    renderAt('/usage', <App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Actualizar' }))
    expect(await screen.findByText(/el runner consulta sus cuotas/)).toBeVisible()
    expect(
      fetch.mock.calls.some(
        ([url, init]) => String(url).includes('/api/usage/refresh') && init?.method === 'POST',
      ),
    ).toBe(true)
  })

  it('sin muestras enseña igual los CLIs del runner y explica de dónde salen los datos', async () => {
    stubApi({ '/api/usage/windows': { runners: [{ id: runner, name: 'laptop', windows: [] }] } })
    renderAt('/usage', <App />)
    const claude = await screen.findByRole('region', { name: 'Claude Code' })
    expect(within(claude).getByText('Sin datos')).toBeVisible()
    expect(
      within(claude).getByText(/solo informa de su cuota mientras ejecuta un agente/),
    ).toBeVisible()
    expect(screen.getByRole('region', { name: 'Codex' })).toBeVisible()
    expect(screen.getByText('Aún no hay datos de cuota de este runner.')).toBeVisible()
  })

  it('sin muestras ni CLIs con cuota explica de dónde salen los datos', async () => {
    stubApi({
      '/api/usage/windows': { runners: [{ id: runner, name: 'laptop', windows: [] }] },
      '/api/runners': runners.map((r) => ({ ...r, providers: [] })),
    })
    renderAt('/usage', <App />)
    expect(await screen.findByText('Aún no hay datos de cuota.')).toBeVisible()
  })
})
