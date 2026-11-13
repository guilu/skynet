import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EvalBank, EvalCase } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const escapeCase: EvalCase = {
  id: 'c1',
  runId: 'run-1',
  repositoryId: 'repo',
  repositoryName: 'demo-repo',
  workItemKey: 'ENG-1',
  title: 'Arreglar add()',
  stageKey: 'juzgar',
  baseSha: 'a'.repeat(40),
  headSha: 'c0ffee' + '0'.repeat(34),
  expected: 'debe-suspender',
  fromEscape: true,
  note: null,
  createdBy: 'skynet',
  createdAt: '2026-11-13T10:00:00Z',
  runs: [
    {
      id: 'e1',
      caseId: 'c1',
      pairId: 'p1',
      judge: 'juez',
      lens: 'correctness',
      provider: 'claude',
      model: 'opus',
      workflowRunId: 'eval-1',
      agentRunId: 'a1',
      status: 'DONE',
      score: 4.8,
      passed: true,
      correct: false,
      findings: [],
      summary: 'Todo bien',
      error: null,
      costUsd: 0.1,
      createdAt: '2026-11-13T11:00:00Z',
    },
    {
      id: 'e2',
      caseId: 'c1',
      pairId: 'p1',
      judge: 'juez',
      lens: 'correctness',
      provider: 'claude',
      model: 'sonnet',
      workflowRunId: 'eval-1',
      agentRunId: 'a2',
      status: 'DONE',
      score: 2,
      passed: false,
      correct: true,
      findings: [{ file: 'src/Calc.java', line: 3, severity: 'blocking', text: 'Rompe negativos' }],
      summary: null,
      error: null,
      costUsd: 0.05,
      createdAt: '2026-11-13T11:00:00Z',
    },
  ],
}

const bank: EvalBank = {
  cases: [escapeCase],
  accuracy: [
    {
      judge: 'juez',
      provider: 'claude',
      model: 'sonnet',
      runs: 1,
      correct: 1,
      failed: 0,
      ratio: 1,
    },
    { judge: 'juez', provider: 'claude', model: 'opus', runs: 1, correct: 0, failed: 0, ratio: 0 },
  ],
}

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

afterEach(() => vi.unstubAllGlobals())

describe('Fábrica › Evaluaciones (EV-C)', () => {
  it('enseña el acierto de cada juez y las repeticiones de un caso, con el A/B junto', async () => {
    stubApi({ '/api/factory/evals': bank })
    renderAt('/evals', <App />)
    const accuracy = await screen.findByRole('table', { name: 'Acierto en el banco' })
    const rows = within(accuracy).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('claude · sonnet')
    expect(rows[1]).toHaveTextContent('1 de 1 (100 %)')

    const item = screen.getByRole('region', { name: 'Caso ENG-1' })
    expect(item).toHaveTextContent('Debe suspender')
    expect(item).toHaveTextContent('escape')
    expect(item).toHaveTextContent('c0ffee0 contra aaaaaaa')
    const opus = within(item).getByRole('article', { name: 'juez · claude · opus' })
    expect(opus).toHaveTextContent('Aprueba')
    expect(opus).toHaveTextContent('Falló')
    expect(opus).toHaveTextContent('nota 4,8')
    const sonnet = within(item).getByRole('article', { name: 'juez · claude · sonnet' })
    expect(sonnet).toHaveTextContent('Suspende')
    expect(sonnet).toHaveTextContent('Acertó')
    expect(sonnet).toHaveTextContent('src/Calc.java:3 Rompe negativos')
    expect(within(item).getByRole('link', { name: 'ENG-1' })).toHaveAttribute('href', '/runs/run-1')
  })

  it('repite un caso con dos jueces y dice antes cuántas invocaciones serán', async () => {
    const fetch = stubApi({
      '/api/factory/evals': bank,
      '/api/eval-cases/c1/judges': [
        { agent: 'juez', lens: 'correctness', provider: 'claude', model: 'opus', judge: true },
        { agent: 'otro', lens: null, provider: 'codex', model: null, judge: false },
      ],
      'POST /api/eval-cases/c1/runs': [],
    })
    renderAt('/evals', <App />)
    const item = await screen.findByRole('region', { name: 'Caso ENG-1' })
    fireEvent.click(within(item).getByRole('button', { name: 'Repetir con…' }))
    const form = await within(item).findByRole('group', { name: 'Repetir con…' })
    await waitFor(() => expect(within(form).getByLabelText('Juez A')).toHaveValue('juez'))
    expect(form).toHaveTextContent('Será 1 invocación real del modelo.')
    fireEvent.click(within(form).getByLabelText(/Comparar con otro juez/))
    fireEvent.change(within(form).getByLabelText('Juez B'), { target: { value: 'otro' } })
    fireEvent.change(within(form).getByLabelText('Modelo del juez B'), {
      target: { value: 'gpt-5' },
    })
    expect(form).toHaveTextContent('Serán 2 invocaciones reales del modelo.')
    fireEvent.click(within(form).getByRole('button', { name: 'Lanzar' }))
    await waitFor(() => {
      const call = fetch.mock.calls.find(
        ([url, init]) => String(url).endsWith('/c1/runs') && init?.method === 'POST',
      )
      expect(call && JSON.parse(String(call[1]?.body))).toEqual({
        judges: [
          { agent: 'juez', model: null },
          { agent: 'otro', model: 'gpt-5' },
        ],
      })
    })
  })

  it('avisa cuando el banco está vacío', async () => {
    stubApi({ '/api/factory/evals': { cases: [], accuracy: [] } })
    renderAt('/evals', <App />)
    expect(await screen.findByText('El banco está vacío.')).toBeInTheDocument()
  })
})
