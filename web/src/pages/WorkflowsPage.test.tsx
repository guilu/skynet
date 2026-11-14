import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkflowOverview, WorkflowSummary } from '../api'
import App from '../App'
import { overviewChips, providerInfo } from '../components/workflow/overview'
import { mockFetch, renderAt } from '../test/render'

const version = (n: number, status: 'PUBLISHED' | 'DRAFT') => ({
  id: `v${n}${status}`,
  version: n,
  status,
  createdAt: '2026-11-09T10:00:00Z',
  updatedAt: '2026-11-09T10:00:00Z',
  publishedAt: status === 'PUBLISHED' ? '2026-11-09T10:00:00Z' : null,
})

const overview = (extra: Partial<WorkflowOverview>): WorkflowOverview => ({
  stages: 1,
  parallelChildren: 0,
  gates: 0,
  judges: 0,
  providers: [],
  fallbackProviders: [],
  lanes: [],
  inputs: [],
  delivers: false,
  readOnly: false,
  ...extra,
})

const workflow = (
  key: string,
  name: string,
  o: Partial<WorkflowOverview>,
  draft = false,
): WorkflowSummary => ({
  id: key,
  key,
  name,
  description: null,
  published: version(1, 'PUBLISHED'),
  draft: draft ? version(2, 'DRAFT') : null,
  createdAt: '2026-11-06T10:00:00Z',
  archivedAt: null,
  overview: overview(o),
})

const IMPLEMENT = workflow(
  'implementar-tarea',
  'Implementar una tarea',
  {
    stages: 7,
    gates: 1,
    judges: 2,
    providers: ['antigravity', 'claude-code', 'codex'],
    lanes: ['spec', 'implement', 'review', 'deliver'],
    delivers: true,
  },
  true,
)
const REVIEW = workflow('revisar-codigo', 'Revisar código', {
  stages: 2,
  parallelChildren: 3,
  providers: ['claude-code', 'codex'],
  lanes: ['review'],
  readOnly: true,
  inputs: [{ name: 'foco', required: false }],
})

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      mockFetch({
        '/actuator/health': { status: 'UP' },
        '/api/auth/session': { username: 'admin' },
        '/api/workflows': [IMPLEMENT, REVIEW],
        '/api/work-items': [],
      }),
    ),
  )
}

describe('tarjetas de workflow', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('resume fases, aprobaciones, jueces, entrega, lectura y datos', () => {
    expect(overviewChips(IMPLEMENT.overview!).map((c) => c.text)).toEqual([
      '7 fases',
      '1 aprobación',
      '2 jueces',
      'Entrega por PR',
    ])
    expect(overviewChips(REVIEW.overview!).map((c) => c.text)).toEqual([
      '2 fases · 3 a la vez',
      'Solo lee',
      'Pide 1 dato',
    ])
  })

  it('nombra los proveedores conocidos y deja los demás con su clave', () => {
    expect(providerInfo('codex')).toEqual({ name: 'Codex', mono: 'Cx' })
    expect(providerInfo('repository').name).toBe('El del repositorio')
    expect(providerInfo('ollama')).toEqual({ name: 'ollama', mono: 'O' })
  })

  it('enseña etapas y proveedores, y filtra con los chips', async () => {
    stubApi()
    renderAt('/workflows', <App />)
    const list = await screen.findByRole('list', { name: 'Workflows' })
    const card = within(list)
      .getByRole('link', { name: /Implementar una tarea/ })
      .closest('li')!
    expect(card).toHaveTextContent('Etapas: Especificación, Implementación, Revisión, Entrega.')
    expect(card).toHaveTextContent('Proveedores: Antigravity · Claude Code · Codex')
    expect(within(card).getByRole('list', { name: 'Qué hace' })).toHaveTextContent('2 jueces')

    fireEvent.click(screen.getByRole('button', { name: 'Solo leen 1' }))
    expect(screen.getByRole('button', { name: 'Solo leen 1' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(within(list).queryByText('Implementar una tarea')).not.toBeInTheDocument()
    expect(within(list).getByText('Revisar código')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Solo leen 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Antigravity' }))
    expect(within(list).getByText('Implementar una tarea')).toBeVisible()
    expect(within(list).queryByText('Revisar código')).not.toBeInTheDocument()
  })
})
