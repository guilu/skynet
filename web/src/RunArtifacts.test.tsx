import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import artifacts from '../../fixtures/contracts/artifacts.json'
import runView from '../../fixtures/contracts/run-view.json'
import runners from '../../fixtures/contracts/runners.json'
import verifications from '../../fixtures/contracts/verifications.json'
import App from './App'
import { mockFetch, renderAt } from './test/render'

// Monaco no funciona en jsdom: el visor se sustituye por un bloque con el mismo texto.
vi.mock('./components/run/MonacoView', () => ({
  default: ({ text, label }: { text: string; label: string }): ReactNode => (
    <pre aria-label={label}>{text}</pre>
  ),
}))

class SilentEventSource {
  onopen = null
  onerror = null
  onmessage = null
  close() {}
}

const runId = runView.id
const agent = runView.stages[0].agents[0]
const [diffArtifact, reportArtifact] = artifacts
const verification = verifications[0]

const diff =
  'diff --git a/calc.py b/calc.py\n' +
  'index 1..2 100644\n' +
  '--- a/calc.py\n' +
  '+++ b/calc.py\n' +
  '@@ -1,2 +1,2 @@\n' +
  ' def add(a, b):\n' +
  '-    return a - b\n' +
  '+    return a + b\n'
const changes = {
  branch: 'skynet/tkm-1/0b6a3c1e',
  baseCommit: 'e423d4a4c8acc52a200b70a76f322933eb3d6679',
  headCommit: 'c3c0c205acb0adea069f8d5ee5d835a24da433b1',
  commits: [
    {
      sha: 'c3c0c205acb0adea069f8d5ee5d835a24da433b1',
      author: 'bot',
      email: 'bot@skynet',
      date: '2026-10-05T09:00:40Z',
      subject: 'fix add',
    },
  ],
  files: [
    {
      path: 'calc.py',
      status: 'M',
      insertions: 1,
      deletions: 1,
      binary: false,
      diffOffset: 0,
      diffLength: diff.length,
    },
  ],
  uncommittedFiles: 0,
}
const changesJson = JSON.stringify(changes)
const changesArtifact = {
  ...diffArtifact,
  id: 'changes',
  type: 'GIT_CHANGES',
  name: 'changes.json',
  mediaType: 'application/json',
  size: changesJson.length,
}
const diffSummary = { ...diffArtifact, size: diff.length }
const logArtifact = {
  ...diffArtifact,
  id: 'log',
  type: 'LOG',
  name: 'agent.ndjson',
  mediaType: 'application/x-ndjson',
  size: 20,
}
const report = {
  total: 12,
  failed: 1,
  errors: 0,
  skipped: 2,
  reports: ['build/test-results/test/TEST-CalcTest.xml'],
  unreadable: [],
  failures: [
    {
      suite: 'CalcTest',
      className: 'CalcTest',
      name: 'subtracts',
      kind: 'failure',
      type: 'AssertionError',
      message: 'expected 3 but was -1',
      details: 'at CalcTest.java:12',
    },
  ],
}
const reportJson = JSON.stringify(report)

const content = (id: string, offset: number, limit: number, body: string) => ({
  [`/api/artifacts/${id}/content?offset=${offset}&limit=${limit}`]: new Response(body, {
    headers: { 'X-Artifact-Size': String(new TextEncoder().encode(body).length) },
  }),
})

function openRun(tab: string) {
  vi.stubGlobal('EventSource', SilentEventSource)
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      [`/api/workflow-runs/${runId}`]: runView,
      '/api/runners': runners,
      [`/api/agent-runs/${agent.id}/verifications`]: verifications,
      [`POST /api/agent-runs/${agent.id}/verifications`]: {
        ...verification,
        id: 'otra',
        trigger: 'MANUAL',
        status: 'QUEUED',
        live: true,
      },
      [`/api/agent-runs/${agent.id}/artifacts`]: [
        changesArtifact,
        diffSummary,
        logArtifact,
        { ...reportArtifact, size: reportJson.length },
      ],
      ...content('changes', 0, changesJson.length, changesJson),
      ...content(diffArtifact.id, 0, diff.length, diff),
      ...content(reportArtifact.id, 0, reportJson.length, reportJson),
      ...content('log', 0, 512 * 1024, '{"type":"system"}\n'),
    }),
  )
  vi.stubGlobal('fetch', fetch)
  renderAt(`/runs/${runId}?tab=${tab}`, <App />)
  return fetch
}

describe('artefactos y verificación', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('muestra los archivos cambiados, su diff, los commits y los logs', async () => {
    openRun('artifacts')
    const panel = await screen.findByRole('tabpanel')
    const files = await within(panel).findByRole('list', { name: 'Archivos modificados' })
    expect(within(files).getByRole('button', { name: /calc\.py/ })).toBeInTheDocument()
    expect(within(files).getByText('+1')).toBeInTheDocument()
    expect((await within(panel).findByLabelText('Diff de calc.py')).textContent).toContain(
      '+    return a + b',
    )
    expect(within(panel).getByText(/fix add/)).toBeInTheDocument()

    fireEvent.click(within(panel).getByRole('button', { name: 'Salida del agente (NDJSON)' }))
    expect(await within(panel).findByLabelText('Salida del agente (NDJSON)')).toHaveTextContent(
      '{"type":"system"}',
    )
  })

  it('compara lo declarado con lo verificado y reejecuta la verificación', async () => {
    const fetch = openRun('verification')
    const panel = await screen.findByRole('tabpanel')
    expect(
      within(panel).getByRole('heading', { name: 'Declarado por el agente' }),
    ).toBeInTheDocument()
    const verified = within(panel)
      .getByRole('heading', { name: 'Verificado por Skynet' })
      .closest('section')!
    expect(await within(verified).findByText('./gradlew test')).toBeInTheDocument()
    expect(within(verified).getByText('Fallida')).toBeInTheDocument()
    expect(within(verified).getByText('12 tests · 1 fallidos · 2 omitidos')).toBeInTheDocument()
    expect(await within(verified).findByText('CalcTest.subtracts')).toBeInTheDocument()
    expect(within(verified).getByText(/expected 3 but was -1/)).toBeInTheDocument()

    fireEvent.click(within(panel).getByRole('button', { name: 'Reejecutar verificación' }))
    await waitFor(() =>
      expect(
        fetch.mock.calls.filter(
          ([url, init]) =>
            String(url).endsWith(`/api/agent-runs/${agent.id}/verifications`) &&
            init?.method === 'POST',
        ),
      ).toHaveLength(1),
    )
  })

  it('la cabecera muestra el resultado de la verificación', async () => {
    openRun('summary')
    await waitFor(() =>
      expect(document.querySelector('.run-header')).toHaveTextContent('Verificación'),
    )
    expect(document.querySelector('.run-header')).toHaveTextContent('11/12 tests')
  })
})
