import { expect, test, type Page } from '@playwright/test'
import {
  expectAccessible,
  fakeClaudeProcesses,
  login,
  postApi,
  publishWorkflowViaApi,
  workItemViaApi,
} from './helpers.ts'

/**
 * Reintentos y cancelación (W6), criterio de aceptación junto con `retries.e2e.ts`: cancelar una
 * ejecución con dos agentes en paralelo no deja procesos de fake-claude en el runner, y «Reintentar
 * fase» en una ejecución fallida la vuelve a poner en marcha y la termina «Completada».
 */

test.use({ baseURL: 'http://localhost:4173' })

const out = process.env.E2E_OUT ?? '/tmp'

async function launched(page: Page, yaml: (key: string) => string): Promise<string> {
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const key = `w6-${Date.now().toString(36)}`
  const definitionId = await publishWorkflowViaApi(page, yaml(key))
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const run = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    definitionId,
  })
  return run.id
}

async function status(page: Page, runId: string): Promise<string> {
  return (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()).status
}

test('cancelar una ejecución con dos agentes en paralelo no deja procesos vivos', async ({
  page,
}) => {
  test.setTimeout(180_000)
  const runId = await launched(page, (key) =>
    [
      `id: ${key}`,
      'agents:',
      '  uno:',
      '    prompt: Revisa la primera mitad',
      '  dos:',
      '    prompt: Revisa la segunda mitad',
      'stages:',
      '  - id: revisar',
      '    type: parallel',
      '    children:',
      '      - agent: uno',
      '      - agent: dos',
      '  - id: final',
      '    type: command',
      '    command: "true"',
      '    dependsOn: [revisar]',
      '',
    ].join('\n'),
  )
  await page.goto(`/runs/${runId}`)
  const header = page.locator('header.run-header')
  // Los dos hijos trabajan a la vez.
  await expect.poll(() => fakeClaudeProcesses().length, { timeout: 60_000 }).toBeGreaterThan(1)

  await header.getByRole('button', { name: 'Cancelar ejecución' }).click()
  await header.getByRole('button', { name: 'Sí, cancelar' }).click()

  await expect(header.locator('.hero-top').getByText('Cancelada')).toBeVisible({
    timeout: 30_000,
  })
  await expect(header.getByRole('button', { name: 'Cancelar ejecución' })).toHaveCount(0)
  await expect.poll(fakeClaudeProcesses, { timeout: 15_000 }).toHaveLength(0)
  const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
  expect(
    run.stages.map((s: { stageKey: string; status: string }) => [s.stageKey, s.status]),
  ).toEqual([
    ['revisar', 'CANCELLED'],
    ['final', 'CANCELLED'],
  ])
})

test('«Reintentar fase» en una ejecución fallida la termina «Completada»', async ({ page }) => {
  test.setTimeout(240_000)
  const marker = `${out}/w6c-${Date.now().toString(36)}`
  const runId = await launched(page, (key) =>
    [
      `id: ${key}`,
      'stages:',
      '  - id: implement',
      '    type: agent',
      '    prompt: Arregla add()',
      '  - id: comprobar',
      '    type: command',
      '    dependsOn: [implement]',
      `    command: ${JSON.stringify(`if [ ! -f ${marker} ]; then touch ${marker}; exit 1; fi; grep -q 'a + b' calc.py`)}`,
      '  - id: cerrar',
      '    type: command',
      '    command: "true"',
      '    dependsOn: [comprobar]',
      '',
    ].join('\n'),
  )
  await expect.poll(() => status(page, runId), { timeout: 120_000 }).toBe('FAILED')

  await page.goto(`/runs/${runId}`)
  const header = page.locator('header.run-header')
  await expect(header.locator('.hero-top').getByText('Fallida')).toBeVisible()
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  await progress.getByRole('button', { name: /^2\. comprobar:/ }).click()
  const detail = progress.locator('.step-detail')
  // La fase que falló ofrece reintentarse, con su alcance.
  await detail.getByRole('button', { name: 'Reintentar fase' }).click()
  const confirm = detail.getByRole('group', { name: 'Confirmar el reintento' })
  await expect(confirm).toContainText('Se reintenta comprobar (intento 2)')
  await expect(confirm).toContainText('Después vuelven a lanzarse cerrar')
  await expectAccessible(page, 'la confirmación de «Reintentar fase»')
  await confirm.getByRole('button', { name: 'Sí, reintentar' }).click()

  await expect(header.locator('.hero-top').getByText('Completada')).toBeVisible({
    timeout: 120_000,
  })
  const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
  expect(
    run.stages.map((s: { stageKey: string; attempt: number; status: string }) => [
      s.stageKey,
      s.attempt,
      s.status,
    ]),
  ).toEqual([
    ['implement', 1, 'SUCCEEDED'],
    ['comprobar', 1, 'FAILED'],
    ['comprobar', 2, 'SUCCEEDED'],
    ['cerrar', 1, 'CANCELLED'],
    ['cerrar', 2, 'SUCCEEDED'],
  ])
  await progress.getByRole('button', { name: /^2\. comprobar:/ }).click()
  await expect(detail).toContainText('intento 2')
  await expect(detail).toContainText('lo pidió admin')
  await expect(detail.getByRole('button', { name: 'Reintentar fase' })).toHaveCount(0)
})
