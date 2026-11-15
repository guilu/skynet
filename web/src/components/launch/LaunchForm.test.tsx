import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import workflows from '../../../../fixtures/contracts/workflows.json'
import version from '../../../../fixtures/contracts/workflow-version.json'
import run from '../../../../fixtures/contracts/run-view.json'
import type { DoneMatch, Repository } from '../../api'
import { mockFetch, renderAt } from '../../test/render'
import { LaunchForm } from './LaunchForm'

const ITEM = 'item-1'
const VERSION = '0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a13'

const repository = {
  id: 'repo-1',
  name: 'akademia',
  agentPolicy: null,
  agentPolicyCustom: false,
} as unknown as Repository

const match: DoneMatch = {
  workItemId: 'item-0',
  key: 'AKA-1',
  title: 'Exportar las notas a PDF',
  similarity: 0.67,
  strong: true,
  self: false,
  pullRequestNumber: 40,
  pullRequestUrl: 'https://github.com/guilu/akademia/pull/40',
  deliveredAt: '2026-11-03T10:00:00Z',
}

/** El primer lanzamiento responde 409 DONE_MATCH; el confirmado, la ejecución. */
function stubApi() {
  const routes = mockFetch({
    '/api/workflows': workflows,
    [`/api/workflow-versions/${VERSION}`]: version,
    '/api/providers': [],
  })
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).endsWith(`/api/work-items/${ITEM}/runs`) && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as { confirmDone?: boolean }
      if (!body.confirmDone) {
        return new Response(
          JSON.stringify({
            status: 409,
            code: 'DONE_MATCH',
            detail:
              'Esto parece ya hecho: AKA-1 «Exportar las notas a PDF» se entregó en la PR #40',
            doneMatches: [match],
          }),
          { status: 409 },
        )
      }
      return new Response(JSON.stringify(run), { status: 201 })
    }
    return routes(input, init)
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const launches = (fetch: ReturnType<typeof stubApi>) =>
  fetch.mock.calls
    .filter(([url, init]) => String(url).endsWith('/runs') && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init?.body)) as { confirmDone?: boolean })

describe('Lanzar algo que parece ya hecho (MV-B)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña con qué se parece y lo lanza solo si se confirma', async () => {
    const fetch = stubApi()
    renderAt(
      `/work-items/${ITEM}`,
      <LaunchForm workItemId={ITEM} repositories={[repository]} defaultRepositoryId="repo-1" />,
    )

    const launch = await screen.findByRole('button', { name: 'Lanzar' })
    await waitFor(() => expect(launch).toBeEnabled())
    fireEvent.click(launch)

    const notice = await screen.findByRole('alert', { name: 'Parece ya hecho' })
    expect(notice).toHaveTextContent('AKA-1 «Exportar las notas a PDF» se entregó en la PR #40')
    expect(notice).toHaveTextContent('(parecido 67 %)')
    expect(screen.getByRole('link', { name: 'PR #40' })).toHaveAttribute(
      'href',
      'https://github.com/guilu/akademia/pull/40',
    )
    expect(screen.getByRole('button', { name: 'Lanzar' })).toBeDisabled()
    expect(launches(fetch)).toEqual([expect.not.objectContaining({ confirmDone: true })])

    // «No lanzar» lo quita y deja lanzar otra vez.
    fireEvent.click(screen.getByRole('button', { name: 'No lanzar' }))
    await waitFor(() =>
      expect(screen.queryByRole('alert', { name: 'Parece ya hecho' })).not.toBeInTheDocument(),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Lanzar' }))

    fireEvent.click(await screen.findByRole('button', { name: 'Lanzar igualmente' }))
    await waitFor(() => expect(launches(fetch)).toHaveLength(3))
    expect(launches(fetch)[2].confirmDone).toBe(true)
  })
})
