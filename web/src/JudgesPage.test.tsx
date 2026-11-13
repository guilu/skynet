import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JudgeStatsRow } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const noisy: JudgeStatsRow = {
  provider: 'claude',
  model: 'opus',
  judge: 'revisor',
  lens: 'security',
  findings: 12,
  refuted: 5,
  irrelevant: 1,
  fixed: 3,
  accepted: 1,
  parked: 2,
  open: 0,
  noise: 0.5,
  hits: 0.25,
  fewData: false,
  noisy: [
    {
      id: 'f-1',
      runId: 'run-7',
      file: 'src/Calc.java',
      line: 12,
      text: 'Redondea mal los negativos',
      outcome: 'refutado',
      outcomeBy: 'skynet',
      outcomeReason: 'Redondea hacia cero a propósito',
      createdAt: '2026-11-09T10:00:00Z',
    },
  ],
}

const quiet: JudgeStatsRow = {
  ...noisy,
  provider: 'codex',
  model: 'gpt',
  judge: 'juez',
  lens: null,
  findings: 3,
  refuted: 0,
  irrelevant: 0,
  fixed: 3,
  accepted: 0,
  parked: 0,
  noise: 0,
  hits: 1,
  fewData: true,
  noisy: [],
}

function stubApi(routes: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      mockFetch({
        '/actuator/health': { status: 'UP' },
        '/api/auth/session': { username: 'admin' },
        ...routes,
      }),
    ),
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('Fábrica › Jueces (RH-C)', () => {
  it('enseña el ruido y el acierto de cada juez, y sus hallazgos con ruido al elegirlo', async () => {
    stubApi({ '/api/factory/judges?days=30': [noisy, quiet] })
    renderAt('/judges', <App />)
    const table = await screen.findByRole('table', { name: 'Jueces' })
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('revisor · seguridad')
    expect(rows[1]).toHaveTextContent('claude · opus')
    expect(rows[1]).toHaveTextContent('50 %')
    expect(rows[1]).toHaveTextContent('25 %')
    expect(rows[1]).not.toHaveTextContent('pocos datos')
    expect(rows[2]).toHaveTextContent('juez · general')
    expect(rows[2]).toHaveTextContent('pocos datos')
    expect(screen.getByRole('button', { name: '30 días' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(within(table).getByRole('button', { name: 'revisor · seguridad' }))
    const noise = await screen.findByRole('region', { name: 'Ruido de revisor · seguridad' })
    expect(noise).toHaveTextContent('Refutado')
    expect(noise).toHaveTextContent('src/Calc.java:12')
    expect(noise).toHaveTextContent('Redondea hacia cero a propósito')
    expect(within(noise).getByRole('link')).toHaveAttribute('href', '/runs/run-7')
  })

  it('cambia de periodo y avisa cuando no hay hallazgos', async () => {
    stubApi({ '/api/factory/judges?days=30': [noisy], '/api/factory/judges?days=7': [] })
    renderAt('/judges', <App />)
    await screen.findByRole('table', { name: 'Jueces' })
    fireEvent.click(screen.getByRole('button', { name: '7 días' }))
    expect(await screen.findByText('Ningún hallazgo en 7 días.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '7 días' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('mira una sola clase de hallazgo (AP-A)', async () => {
    stubApi({
      '/api/factory/judges?days=30': [noisy, quiet],
      '/api/factory/judges?days=30&category=caso-limite': [quiet],
    })
    renderAt('/judges', <App />)
    const table = await screen.findByRole('table', { name: 'Jueces' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    fireEvent.change(screen.getByLabelText('Clase'), { target: { value: 'caso-limite' } })
    await waitFor(() =>
      expect(
        within(screen.getByRole('table', { name: 'Jueces' })).getAllByRole('row'),
      ).toHaveLength(2),
    )
    expect(screen.getByLabelText('Clase')).toHaveValue('caso-limite')
  })
})
