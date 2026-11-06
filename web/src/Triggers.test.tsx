import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import rules from '../../fixtures/contracts/trigger-rules.json'
import settings from '../../fixtures/contracts/trigger-settings.json'
import launches from '../../fixtures/contracts/trigger-launches.json'
import queue from '../../fixtures/contracts/run-queue.json'
import type { Repository } from './api'
import { TriggerSettingsSection } from './components/github/TriggerSettings'
import { RepositoryTriggers } from './components/project/RepositoryTriggers'
import { mockFetch, renderAt } from './test/render'

const REPO = '0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a03'

const repository: Repository = {
  id: REPO,
  projectId: 'p1',
  name: 'demo',
  localPath: '/r',
  remoteUrl: null,
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

const bodyOf = (fetch: ReturnType<typeof stubApi>, method: string, path: string) => {
  const call = fetch.mock.calls.find(
    ([url, init]) => String(url).endsWith(path) && init?.method === method,
  )
  return call ? JSON.parse(String(call[1]?.body)) : undefined
}

describe('Disparadores (DS-C)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('Ajustes → GitHub: interruptor, freno diario y últimas peticiones', async () => {
    const fetch = stubApi({
      '/api/triggers/settings': settings,
      'PUT /api/triggers/settings': { ...settings, enabled: false },
      '/api/triggers/launches?limit=20': launches,
    })
    renderAt('/settings?tab=github', <TriggerSettingsSection />)

    expect(await screen.findByText('Activos')).toBeInTheDocument()
    expect(screen.getByText(/Hoy van/)).toHaveTextContent('Hoy van 3 de 10 ejecuciones')
    const table = await screen.findByRole('table', { name: /Últimas peticiones/ })
    expect(within(table).getByText('guilu/demo-repo#12')).toHaveAttribute(
      'href',
      'https://github.com/guilu/demo-repo/issues/12',
    )
    expect(within(table).getByText('Lanzada')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Apagar' }))
    expect(await screen.findByText('Apagados')).toBeInTheDocument()
    expect(bodyOf(fetch, 'PUT', '/api/triggers/settings')).toEqual({ enabled: false })
  })

  it('en la ficha del repositorio: la cola y los disparadores, con la próxima hora', async () => {
    const fetch = stubApi({
      [`/api/repositories/${REPO}/run-queue`]: queue,
      [`PUT /api/repositories/${REPO}/run-queue`]: { ...queue, maxConcurrentRuns: 3, custom: true },
      [`/api/repositories/${REPO}/triggers`]: rules,
      [`POST /api/triggers/${rules[1].id}/fire`]: rules[1],
    })
    renderAt('/projects/p1', <RepositoryTriggers repository={repository} editable />)

    const cola = await screen.findByRole('region', { name: 'Ejecuciones a la vez en demo' })
    expect(await within(cola).findByText(/en cola/)).toBeInTheDocument()
    expect(within(cola).getByRole('link', { name: 'la siguiente' })).toHaveAttribute(
      'href',
      `/runs/${queue.queued[0]}`,
    )

    const list = await screen.findByRole('region', { name: 'Disparadores de demo' })
    expect(await within(list).findByText('skynet:feature')).toBeInTheDocument()
    expect(within(list).getByText('0 0 9 * * MON')).toBeInTheDocument()
    expect(within(list).getByText(/Próxima:/)).toBeInTheDocument()
    expect(within(list).getByText('lanzó')).toBeInTheDocument()

    fireEvent.click(within(list).getByRole('button', { name: 'Disparar ya' }))
    await waitFor(() =>
      expect(
        fetch.mock.calls.some(
          ([url, init]) =>
            String(url).endsWith(`/api/triggers/${rules[1].id}/fire`) && init?.method === 'POST',
        ),
      ).toBe(true),
    )

    fireEvent.click(within(cola).getByRole('button', { name: /Cambiar las ejecuciones/ }))
    fireEvent.change(within(cola).getByLabelText(/Ejecuciones a la vez/), {
      target: { value: '3' },
    })
    fireEvent.click(within(cola).getByRole('button', { name: 'Guardar' }))
    expect(await within(cola).findByText('propio')).toBeInTheDocument()
    expect(bodyOf(fetch, 'PUT', `/api/repositories/${REPO}/run-queue`)).toEqual({
      maxConcurrentRuns: 3,
    })
  })

  it('por despliegue (DL-D): se ve qué vigila y se crea apagado', async () => {
    const fetch = stubApi({
      [`/api/repositories/${REPO}/run-queue`]: queue,
      [`/api/repositories/${REPO}/triggers`]: rules,
      [`POST /api/repositories/${REPO}/triggers`]: rules[2],
      '/api/workflows': [{ key: 'humo', name: 'Pruebas de humo' }],
    })
    renderAt('/projects/p1', <RepositoryTriggers repository={repository} editable />)

    const list = await screen.findByRole('region', { name: 'Disparadores de demo' })
    const item = (await within(list).findByText(/Al quedar sano/)).closest('li') as HTMLElement
    expect(within(item).getByText('omarchy')).toBeInTheDocument()
    expect(within(item).getByText('Desactivado')).toBeInTheDocument()
    expect(within(item).getByText(/nunca por un SHA ya sustituido/)).toBeInTheDocument()
    expect(within(item).getByText('se saltó')).toBeInTheDocument()
    expect(within(item).queryByRole('button', { name: 'Disparar ya' })).not.toBeInTheDocument()

    fireEvent.click(within(list).getByRole('button', { name: 'Nuevo disparador en demo' }))
    const sheet = await screen.findByRole('dialog')
    fireEvent.click(within(sheet).getByLabelText(/Al desplegar/))
    fireEvent.change(await within(sheet).findByRole('combobox', { name: /Workflow/ }), {
      target: { value: 'humo' },
    })
    fireEvent.change(within(sheet).getByRole('combobox', { name: /Cuándo/ }), {
      target: { value: 'UNHEALTHY' },
    })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Crear disparador' }))
    await waitFor(() =>
      expect(bodyOf(fetch, 'POST', `/api/repositories/${REPO}/triggers`)).toEqual({
        kind: 'DEPLOYMENT',
        workflowKey: 'humo',
        environment: 'omarchy',
        health: 'UNHEALTHY',
        enabled: false,
      }),
    )
  })
})
