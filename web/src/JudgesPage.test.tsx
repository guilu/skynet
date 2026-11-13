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
  judged: 12,
  kept: 9,
  escaped: 2,
  rejected: 1,
  closed: 0,
  fewOutcomes: false,
  calibration: [
    {
      label: '< 4',
      from: 0,
      to: 4,
      judged: 0,
      kept: 0,
      escaped: 0,
      rejected: 0,
      closed: 0,
      keptRatio: 0,
    },
    {
      label: '4–4,5',
      from: 4,
      to: 4.5,
      judged: 4,
      kept: 3,
      escaped: 1,
      rejected: 0,
      closed: 0,
      keptRatio: 0.75,
    },
    {
      label: '≥ 4,5',
      from: 4.5,
      to: null,
      judged: 8,
      kept: 6,
      escaped: 1,
      rejected: 1,
      closed: 0,
      keptRatio: 0.75,
    },
  ],
  escapes: [
    {
      runId: 'run-9',
      pullRequestNumber: 12,
      origin: 'revert-pr',
      seenRef: '#13',
      note: null,
      score: 4.8,
      createdAt: '2026-11-12T10:00:00Z',
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
  judged: 0,
  kept: 0,
  escaped: 0,
  rejected: 0,
  closed: 0,
  fewOutcomes: true,
  escapes: [],
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

  it('cuenta lo que pasó con lo que aprobó y lo calibra por nota (EV-B)', async () => {
    stubApi({ '/api/factory/judges?days=30': [noisy, quiet] })
    renderAt('/judges', <App />)
    const table = await screen.findByRole('table', { name: 'Jueces' })
    const rows = within(table).getAllByRole('row')
    expect(rows[0]).toHaveTextContent('Se quedó')
    expect(rows[0]).toHaveTextContent('Escapes')
    expect(rows[0]).toHaveTextContent('Rechazos')
    expect(rows[1]).toHaveTextContent('9 de 12')
    expect(rows[2]).toHaveTextContent('—')

    fireEvent.click(within(table).getByRole('button', { name: 'revisor · seguridad' }))
    const panel = await screen.findByRole('region', { name: 'Calibración de revisor · seguridad' })
    const bands = within(within(panel).getByRole('table', { name: 'Por nota' })).getAllByRole('row')
    expect(bands[2]).toHaveTextContent('4–4,5')
    expect(bands[2]).toHaveTextContent('3 (75 %)')
    expect(bands[3]).toHaveTextContent('≥ 4,5')
    expect(panel).toHaveTextContent('PR #12 · nota 4,8 · revertida con la PR #13')
    expect(within(panel).getByRole('link', { name: 'PR #12' })).toHaveAttribute(
      'href',
      '/runs/run-9',
    )
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
