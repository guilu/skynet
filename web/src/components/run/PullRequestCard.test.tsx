import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CiCheckView, MergeView, PullRequestView, Run } from '../../api'
import { mockFetch, renderAt } from '../../test/render'
import { PullRequestCard } from './PullRequestCard'

const run = { id: 'r1', stages: [{ type: 'github-pr' }] } as unknown as Run

const pr: PullRequestView = {
  repositoryId: 'repo',
  fullName: 'guilu/demo-repo',
  branch: 'skynet/ENG-1',
  base: 'main',
  status: 'OPEN',
  headSha: 'c0ffee0000000000000000000000000000000001',
  number: 12,
  url: 'https://github.com/guilu/demo-repo/pull/12',
  title: 'ENG-1: Arreglar add()',
  error: null,
  updatedAt: '2026-10-31T10:00:00Z',
}

const ci: CiCheckView = {
  stageRunId: 's2',
  stageKey: 'ci',
  status: 'PASSED',
  headSha: pr.headSha!,
  round: 1,
  checks: [
    { name: 'test', state: 'success', url: 'https://github.com/runs/1' },
    { name: 'lint', state: 'pending', url: null },
  ],
  error: null,
  waitingSince: '2026-10-31T10:00:00Z',
  updatedAt: '2026-10-31T10:05:00Z',
}

describe('La tarjeta de la PR', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña la PR con su enlace, la rama, el commit, la CI check a check y la fusión', async () => {
    const merge: MergeView = {
      stageRunId: 's4',
      method: 'squash',
      status: 'MERGED',
      expectedSha: pr.headSha!,
      mergedSha: 'e'.repeat(40),
      branchDeleted: true,
      error: null,
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/api/workflow-runs/r1/pull-requests': [{ ...pr, status: 'MERGED' }],
          '/api/workflow-runs/r1/ci-checks': [ci],
          '/api/workflow-runs/r1/merges': [merge],
        }),
      ),
    )
    renderAt('/', <PullRequestCard run={run} />)

    const card = await screen.findByRole('region', { name: 'Pull request' })
    expect(within(card).getByRole('link', { name: 'PR #12' })).toHaveAttribute('href', pr.url)
    expect(card).toHaveTextContent('Fusionada')
    expect(card).toHaveTextContent('skynet/ENG-1 → main')
    expect(card).toHaveTextContent('c0ffee0')
    expect(card).toHaveTextContent('En verde 1 ronda de corrección')
    expect(card).toHaveTextContent('Fusionada (eeeeeee)')
    const checks = within(card).getByRole('list', { name: 'Checks de la CI' })
    expect(checks).toHaveTextContent('Pasótest')
    expect(checks).toHaveTextContent('En cursolint')
  })

  it('dice por qué no se fusionó', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/api/workflow-runs/r1/pull-requests': [pr],
          '/api/workflow-runs/r1/ci-checks': [],
          '/api/workflow-runs/r1/merges': [
            {
              stageRunId: 's4',
              method: 'squash',
              status: 'FAILED',
              expectedSha: pr.headSha,
              mergedSha: null,
              branchDeleted: false,
              error: 'La CI ya no está en verde: falló en lint',
            },
          ],
        }),
      ),
    )
    renderAt('/', <PullRequestCard run={run} />)

    const card = await screen.findByRole('region', { name: 'Pull request' })
    expect(card).toHaveTextContent('No se fusionó')
    expect(card).toHaveTextContent('La CI ya no está en verde: falló en lint')
  })
})
