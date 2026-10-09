import { expect, test } from '@playwright/test'
import { expectAccessible, launchViaApi, login } from './helpers.ts'

/**
 * Archivar y eliminar en la web (AE-C): una ejecución terminada se archiva, su worktree impide
 * eliminarla hasta que se borra desde el propio panel, y después se elimina. Un proyecto se archiva,
 * sale de la lista, aparece con el chip «Archivados» y se restaura.
 */

// Sin el proxy cortable de run.e2e.ts: directamente a vite preview.
test.use({ baseURL: 'http://localhost:4173' })

test('archivar, limpiar el worktree y eliminar una ejecución; archivar y restaurar un proyecto', async ({
  page,
}) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const runId = await launchViaApi(page, 'Arregla add() en calc.py')
  const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
  const project = await (await page.request.get(`/api/projects/${run.projectId}`)).json()
  const header = page.locator('header.run-header')

  await test.step('archivar la ejecución terminada', async () => {
    await page.goto(`/runs/${runId}`)
    await expect(header.getByRole('heading', { level: 1 }).getByText('Completada')).toBeVisible({
      timeout: 90_000,
    })
    await header.getByRole('button', { name: /^Más acciones: / }).click()
    await page.getByRole('menuitem', { name: 'Archivar' }).click()
    await expect(page.getByRole('status').filter({ hasText: /Archivado el/ })).toBeVisible()
    await expectAccessible(page, 'ejecución archivada')
  })

  await test.step('el worktree bloquea la eliminación hasta que se borra', async () => {
    await header.getByRole('button', { name: /^Más acciones: / }).click()
    await page.getByRole('menuitem', { name: 'Eliminar…' }).click()
    const sheet = page.getByRole('dialog', { name: 'Eliminar la ejecución' })
    await expect(sheet.getByText(/Se borrará:/)).toBeVisible()
    const confirm = sheet.getByRole('button', { name: 'Eliminar definitivamente' })
    await expect(sheet.getByRole('alert')).toContainText('worktree')
    await expect(confirm).toBeDisabled()
    await expectAccessible(page, 'panel de eliminar')

    await sheet.getByRole('button', { name: 'Eliminar el worktree' }).click()
    await expect(confirm).toBeEnabled({ timeout: 60_000 })
    await confirm.click()
    await page.waitForURL(`**/work-items/${run.workItemId}`)
    expect((await page.request.get(`/api/workflow-runs/${runId}`)).status()).toBe(404)
  })

  await test.step('archivar un proyecto lo saca de la lista', async () => {
    await page.goto(`/projects/${project.id}`)
    const name = `Más acciones: ${project.key} · ${project.name}`
    await page.getByRole('button', { name }).click()
    await page.getByRole('menuitem', { name: 'Archivar' }).click()
    await expect(page.getByRole('status').filter({ hasText: /Archivado el/ })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Nuevo trabajo' })).toHaveCount(0)

    await page.goto('/projects')
    const row = page.getByRole('link', { name: new RegExp(project.key) })
    await expect(page.getByRole('heading', { level: 1, name: 'Proyectos' })).toBeVisible()
    await expect(row).toHaveCount(0)
    await page.getByRole('button', { name: 'Archivados' }).click()
    await expect(row).toBeVisible()
    await expectAccessible(page, 'proyectos archivados')

    await page.getByRole('button', { name }).click()
    await page.getByRole('menuitem', { name: 'Restaurar' }).click()
    await expect(row).toHaveCount(0)
    await page.getByRole('button', { name: 'Archivados' }).click()
    await expect(row).toBeVisible()
  })
})
