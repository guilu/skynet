import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import deployments from '../../fixtures/contracts/deployments.json'
import detail from '../../fixtures/contracts/deployment-detail.json'
import checks from '../../fixtures/contracts/health-checks.json'
import reconciled from '../../fixtures/contracts/deployment-reconciled.json'
import type { Deployment, Repository } from './api'
import { HealthCheckSection } from './components/deployment/HealthCheckSection'
import { ProjectDeployments } from './components/deployment/ProjectDeployments'
import { gitHubRepo } from './components/deployment/deployment'
import { mockFetch, renderAt } from './test/render'

const REPO = '0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a03'
const [deployed, superseded] = deployments as Deployment[]

/** Un despliegue posterior que falló: no cambia lo desplegado. */
const failed: Deployment = {
  ...deployed,
  id: '0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a44',
  externalId: '3141592700',
  commitSha: 'abcdef0123456789abcdef0123456789abcdef01',
  ref: 'claude/roto',
  pullRequestNumber: null,
  url: null,
  status: 'FAILED',
  health: 'UNKNOWN',
  error: 'docker compose pull falló',
  healthDetail: null,
  healthCheckedAt: null,
  healthAttempts: 0,
}

const repository: Repository = {
  id: REPO,
  projectId: 'p1',
  name: 'skynet',
  localPath: '/r',
  remoteUrl: 'git@github.com:guilu/skynet.git',
  defaultBranch: 'main',
  validationCommand: null,
  testReportPaths: [],
  protectedPaths: [],
  agentPolicy: {
    allowedTools: ['Read'],
    permissionMode: 'dontAsk',
    environment: null,
    maxTurns: null,
    maxBudgetUsd: 2,
    timeoutMinutes: 30,
  },
  agentPolicyCustom: false,
  createdAt: '',
  archivedAt: null,
}

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(mockFetch(routes))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const called = (fetch: ReturnType<typeof stubApi>, method: string, path: string) =>
  fetch.mock.calls.find(([url, init]) => String(url).endsWith(path) && init?.method === method)

describe('Despliegues (DL-E)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('separa lo desplegado de lo sano y no atribuye la salud al último pedido', async () => {
    stubApi({
      [`/api/repositories/${REPO}/environments`]: [
        { environment: 'omarchy', deployed, latest: failed },
      ],
      [`/api/repositories/${REPO}/deployments?limit=20`]: [failed, deployed, superseded],
    })
    renderAt(
      '/projects/p1?tab=deployments',
      <ProjectDeployments repositories={[repository]} editable />,
    )

    const env = await screen.findByRole('article', { name: 'Entorno omarchy' })
    expect(within(env).getByText('99179be')).toBeInTheDocument()
    expect(within(env).getByText('#245')).toHaveAttribute(
      'href',
      'https://github.com/guilu/skynet/pull/245',
    )
    expect(within(env).getByText('Desplegado')).toBeInTheDocument()
    expect(within(env).getByText('Sano')).toBeInTheDocument()
    expect(within(env).getByText('Intento 2: Responde 200')).toBeInTheDocument()
    // El último pedido falló: se ve, pero sin salud, y lo desplegado sigue siendo el anterior.
    expect(within(env).getByText('abcdef0')).toBeInTheDocument()
    expect(within(env).getByText('Falló')).toBeInTheDocument()
    expect(within(env).getAllByText('Sano')).toHaveLength(1)

    const table = screen.getByRole('table', { name: 'Despliegues de skynet' })
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(within(rows[3]).getByText('Sustituido')).toBeInTheDocument()
    expect(within(rows[3]).queryByText(/Salud|Sano/)).not.toBeInTheDocument()
  })

  it('el detalle enseña la evidencia y permite volver a comprobar', async () => {
    const fetch = stubApi({
      [`/api/repositories/${REPO}/environments`]: [
        { environment: 'omarchy', deployed, latest: deployed },
      ],
      [`/api/repositories/${REPO}/deployments?limit=20`]: [deployed],
      [`/api/deployments/${deployed.id}`]: detail,
      [`POST /api/deployments/${deployed.id}/health-check`]: { ...deployed, health: 'CHECKING' },
    })
    renderAt(
      '/projects/p1?tab=deployments',
      <ProjectDeployments repositories={[repository]} editable />,
    )

    fireEvent.click(
      await screen.findByRole('button', { name: 'Detalle de lo desplegado en omarchy' }),
    )
    const sheet = await screen.findByRole('dialog', { name: 'Despliegue' })
    const probes = await within(sheet).findByRole('list', { name: 'Intentos de la comprobación' })
    expect(within(probes).getByText('Intento 1: Código malo')).toBeInTheDocument()
    expect(within(probes).getByText('Intento 2: Bien')).toBeInTheDocument()
    expect(within(sheet).getByText(deployed.commitSha)).toBeInTheDocument()
    expect(within(sheet).getByText('docker compose up -d: OK')).toBeInTheDocument()

    fireEvent.click(within(sheet).getByRole('button', { name: 'Volver a comprobar' }))
    await waitFor(() =>
      expect(called(fetch, 'POST', `/api/deployments/${deployed.id}/health-check`)).toBeTruthy(),
    )
  })

  it('reconcilia con GitHub y sin despliegues explica de dónde llegan', async () => {
    const fetch = stubApi({
      [`/api/repositories/${REPO}/environments`]: [],
      [`/api/repositories/${REPO}/deployments?limit=20`]: [],
      [`POST /api/repositories/${REPO}/deployments/reconcile`]: reconciled,
    })
    renderAt(
      '/projects/p1?tab=deployments',
      <ProjectDeployments repositories={[repository]} editable />,
    )

    expect(await screen.findByText(/Aún no ha llegado ningún despliegue/)).toBeInTheDocument()
    fireEvent.click(
      screen.getByRole('button', { name: 'Reconciliar los despliegues de skynet con GitHub' }),
    )
    expect(await screen.findByRole('status')).toHaveTextContent(
      'GitHub: 3 despliegues y 5 estados revisados, 1 cambio aplicado.',
    )
    expect(called(fetch, 'POST', `/api/repositories/${REPO}/deployments/reconcile`)).toBeTruthy()
  })

  it('la ficha del repositorio configura la comprobación de salud', async () => {
    const fetch = stubApi({
      [`/api/repositories/${REPO}/health-checks`]: checks,
      [`PUT /api/repositories/${REPO}/health-checks/staging`]: {
        ...checks[0],
        environment: 'staging',
      },
    })
    renderAt('/projects/p1?tab=config', <HealthCheckSection repository={repository} editable />)

    expect(await screen.findByText('http://omarchy:8081/actuator/health')).toBeInTheDocument()
    expect(screen.getByText(/6 intentos cada 10 s, tras 15 s/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Nueva comprobación de salud en skynet' }))
    const sheet = await screen.findByRole('dialog')
    fireEvent.change(within(sheet).getByLabelText(/Entorno/), { target: { value: 'staging' } })
    fireEvent.change(within(sheet).getByLabelText(/URL/), {
      target: { value: 'https://staging.example/actuator/info' },
    })
    fireEvent.click(within(sheet).getByLabelText(/traer el SHA/))
    fireEvent.click(within(sheet).getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(called(fetch, 'PUT', `/api/repositories/${REPO}/health-checks/staging`)).toBeTruthy(),
    )
    const body = JSON.parse(
      String(called(fetch, 'PUT', `/api/repositories/${REPO}/health-checks/staging`)?.[1]?.body),
    )
    expect(body).toEqual({
      url: 'https://staging.example/actuator/info',
      attempts: 6,
      intervalSeconds: 10,
      initialDelaySeconds: 15,
      timeoutSeconds: 5,
      expectSha: true,
      enabled: true,
    })
  })

  it('reconoce el repositorio de GitHub en la URL remota', () => {
    expect(gitHubRepo('https://github.com/guilu/demo-repo.git')).toBe('guilu/demo-repo')
    expect(gitHubRepo('git@github.com:guilu/skynet.git')).toBe('guilu/skynet')
    expect(gitHubRepo('https://gitlab.com/a/b')).toBeNull()
    expect(gitHubRepo(null)).toBeNull()
  })
})
