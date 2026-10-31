import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import status from '../../fixtures/contracts/github-status.json'
import deliveries from '../../fixtures/contracts/github-deliveries.json'
import App from './App'
import type { GitHubStatus } from './api'
import { mockFetch, renderAt } from './test/render'

const UNCONFIGURED: GitHubStatus = {
  configured: false,
  missing: [
    'SKYNET_GITHUB_APP_ID',
    'SKYNET_GITHUB_PRIVATE_KEY_FILE',
    'SKYNET_GITHUB_WEBHOOK_SECRET',
  ],
  apiUrl: 'https://api.github.com',
  appId: null,
  app: null,
  error: null,
  checkedAt: null,
  installations: [],
  repositories: [
    { ...(status as GitHubStatus).repositories[0], access: 'NOT_INSTALLED', installationId: null },
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

describe('Ajustes > GitHub', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sin App dice qué variables faltan, enseña los pasos y no deja sincronizar', async () => {
    stubApi({ '/api/github': UNCONFIGURED })
    renderAt('/settings?tab=github', <App />)

    expect(await screen.findByText('Sin configurar')).toBeInTheDocument()
    expect(screen.getAllByText('SKYNET_GITHUB_APP_ID').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Sincronizar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Copiar URL del webhook' })).toBeVisible()
    expect(screen.getByRole('cell', { name: 'Pull requests' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Últimas entregas' })).toBeNull()
  })

  it('con App enseña sus instalaciones, el acceso de cada repositorio y las entregas', async () => {
    const fetch = stubApi({
      '/api/github': status,
      'POST /api/github/sync': status,
      '/api/github/deliveries?limit=50': deliveries,
    })
    renderAt('/settings?tab=github', <App />)

    expect(await screen.findByText('Conectada')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'skynet-guilu' })).toHaveAttribute(
      'href',
      'https://github.com/apps/skynet-guilu',
    )
    const repos = screen.getByRole('table', { name: /Repositorios de Skynet/ })
    expect(within(repos).getByText('Con acceso')).toBeInTheDocument()
    const list = await screen.findByRole('table', { name: /Últimas entregas/ })
    expect(within(list).getByText('pull_request.opened')).toBeInTheDocument()
    expect(within(list).getByText('Procesada')).toBeInTheDocument()
    expect(within(list).getByText('ping: GitHub comprueba el webhook')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Sincronizar' }))
    expect(await screen.findByText('Sincronizado.')).toHaveAttribute('role', 'status')
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true)
  })
})
