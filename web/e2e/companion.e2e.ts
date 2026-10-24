import { expect, test } from '@playwright/test'
import {
  expectAccessible,
  launchViaApi,
  login,
  postApi,
  publishWorkflowViaApi,
  workItemViaApi,
} from './helpers.ts'

/**
 * Companion global (CO-B): con ejecuciones simuladas (fake-claude), la mascota de la barra, la
 * campana y los avisos flotantes siguen lo que pasa sin recargar, por el stream global
 * (`after=latest` y filtro de tipos).
 */

test.use({ baseURL: 'http://localhost:4173' })

const failing = (key: string) =>
  [`id: ${key}`, 'stages:', '  - id: falla', '    type: command', '    command: exit 1', ''].join(
    '\n',
  )

test('la mascota, la campana y los avisos siguen las ejecuciones en vivo', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  const mascot = page.getByRole('button', { name: /^Skynet: / })
  await expect(mascot).toBeVisible()
  const toasts = page.locator('.toasts')

  await test.step('una ejecución que termina bien avisa con su clave', async () => {
    const runId = await launchViaApi(page, 'Arregla add() en calc.py')
    const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
    const toast = toasts.locator('.toast').filter({ hasText: 'Workflow completado' })
    await expect(toast).toBeVisible({ timeout: 90_000 })
    await expect(toast).toContainText(run.workItemKey)
    await expect(page.getByRole('button', { name: /^Avisos: \d+ sin leer$/ })).toBeVisible()
    await expectAccessible(page, 'aviso flotante')
  })

  await test.step('la campana guarda el aviso y lo marca leído al cerrarla', async () => {
    await page.getByRole('button', { name: /^Avisos/ }).click()
    const item = page.getByRole('menuitem', { name: /Workflow completado/ })
    await expect(item).toBeVisible()
    await expectAccessible(page, 'campana de avisos')
    await page.keyboard.press('Escape')
    await expect(page.getByRole('button', { name: 'Avisos', exact: true })).toBeVisible()
  })

  await test.step('un fallo avisa y la mascota pone cara de error hasta que lo ves', async () => {
    const definitionId = await publishWorkflowViaApi(
      page,
      failing(`falla-${Date.now().toString(36)}`),
    )
    const { workItemId, repositoryId } = await workItemViaApi(page)
    const run = await postApi(page, `/api/work-items/${workItemId}/runs`, {
      repositoryId,
      definitionId,
    })
    const toast = toasts.locator('.toast').filter({ hasText: 'Ha ocurrido un error' })
    await expect(toast).toBeVisible({ timeout: 90_000 })
    await expect(page.getByRole('button', { name: /^Skynet: Error/ })).toBeVisible()
    await toast.getByRole('link', { name: 'Ver' }).click()
    await page.waitForURL(`**/runs/${run.id}`)
    await expect(page.getByRole('button', { name: /^Skynet: Error/ })).toHaveCount(0)
  })

  await test.step('silenciada, no salen avisos flotantes pero la campana cuenta', async () => {
    await page.goto('/')
    await page.getByRole('button', { name: /^Skynet: / }).click()
    await page.getByRole('menuitem', { name: 'Silenciar 1 h' }).click()
    await launchViaApi(page, 'Arregla add() en calc.py')
    await expect(page.getByRole('button', { name: /^Avisos: \d+ sin leer$/ })).toBeVisible({
      timeout: 90_000,
    })
    await page.getByRole('button', { name: /^Avisos/ }).click()
    await expect(page.getByRole('menuitem', { name: /Workflow completado/ }).first()).toBeVisible({
      timeout: 90_000,
    })
    await page.keyboard.press('Escape')
    await expect(toasts.locator('.toast').filter({ hasText: 'Workflow completado' })).toHaveCount(0)
    await page.getByRole('button', { name: /^Skynet: / }).click()
    await page.getByRole('menuitem', { name: 'Quitar el silencio' }).click()
  })
})
