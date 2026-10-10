import { expect, test } from '@playwright/test'
import {
  expectAccessible,
  login,
  publishWorkflowViaApi,
  service,
  workItemViaApi,
} from './helpers.ts'

/**
 * Motor de workflows (W2): se lanza desde la web un workflow con tres fases, dos en paralelo y una
 * que las une; la vista enseña desde el principio la que espera, se reinicia el control plane a
 * mitad y la ejecución termina «Completada» con un agente por fase, sin duplicados.
 */

// Sin el proxy cortable de run.e2e.ts: directamente a vite preview.
test.use({ baseURL: 'http://localhost:4173' })

const yaml = (key: string) => `id: ${key}
name: Tres fases E2E
description: Dos fases en paralelo y una que las une.

inputs:
  tema:
    type: string
    required: true

agents:
  integrador:
    tools: [Read, Edit, Bash]
    limits:
      maxTurns: 5

stages:
  - id: izquierda
    type: agent
    prompt: "Mira {{inputs.tema}} por la izquierda"
  - id: derecha
    type: agent
    prompt: "Mira {{inputs.tema}} por la derecha"
  - id: union
    type: agent
    agent: integrador
    prompt: "Une lo de {{inputs.tema}}"
    dependsOn: [izquierda, derecha]
    workspaceFrom: derecha
`

test('un workflow de tres fases termina igual aunque se reinicie el control plane', async ({
  page,
}) => {
  test.setTimeout(300_000)
  const key = `tres-${Date.now().toString(36)}`
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  await publishWorkflowViaApi(page, yaml(key))
  const { workItemId } = await workItemViaApi(page)

  await test.step('lanzar el workflow desde la web con sus datos', async () => {
    await page.goto(`/work-items/${workItemId}`)
    await page.getByRole('button', { name: 'Lanzar workflow' }).click()
    const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
    await expect(sheet.getByLabel('Workflow')).toHaveValue('adhoc')
    await sheet.getByLabel('Workflow').selectOption(key)
    await sheet.getByLabel('Tema').fill('la suma')
    const policies = sheet.getByRole('region', { name: 'Política de cada fase' })
    await expect(policies.getByText(/agente integrador/)).toBeVisible()
    await expect(policies.getByRole('listitem')).toHaveCount(3)
    await expectAccessible(page, 'lanzar un workflow')
    await sheet.getByRole('button', { name: 'Lanzar', exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: /Trabajo por API/ })).toBeVisible()
  })

  const tree = page.getByRole('navigation', { name: 'Fases y agentes' })
  const runId = page.url().split('/runs/')[1].split('?')[0]

  await test.step('todas las fases desde el principio; la unión espera', async () => {
    await expect(page.getByRole('link', { name: 'Tres fases E2E' })).toBeVisible()
    await expect(tree.locator('.tree-stages > li')).toHaveCount(3)
    await expect(tree.getByText('Espera a izquierda y derecha')).toBeVisible()
    // Las fases en paralelo ya trabajan: se ven sus herramientas.
    await expect(tree.getByRole('button', { name: /^Read / }).first()).toBeVisible({
      timeout: 45_000,
    })
  })

  await test.step('reiniciar el control plane a mitad', async () => {
    try {
      service('stop', 'control-plane')
    } finally {
      service('start', 'control-plane')
    }
    // Las sesiones de la web eran del proceso anterior: hay que volver a entrar.
    await page.goto(`/runs/${runId}`)
    await login(page)
  })

  await test.step('la ejecución termina «Completada», con un agente por fase', async () => {
    const header = page.locator('header.run-header')
    await expect(header.getByRole('heading', { level: 1 }).getByText('Completada')).toBeVisible({
      timeout: 120_000,
    })
    await expect(tree.locator('.tree-stage').filter({ hasText: 'Completada' })).toHaveCount(3)
    await expect(tree.getByText('Después de izquierda y derecha')).toBeVisible()
    const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
    expect(
      run.stages.map((s: { stageKey: string; agents: unknown[] }) => [s.stageKey, s.agents.length]),
    ).toEqual([
      ['izquierda', 1],
      ['derecha', 1],
      ['union', 1],
    ])
  })
})
