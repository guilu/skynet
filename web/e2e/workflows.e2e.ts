import { expect, test, type Page } from '@playwright/test'
import { expectAccessible, login } from './helpers.ts'

/**
 * Definiciones de workflow en la web (W1-B): se importa un YAML en el alta, el editor marca un error
 * mientras se escribe, se crea el borrador, se publica, «Editar» abre el borrador de la v2 y se
 * descarta sin tocar la v1.
 */

// Sin el proxy cortable de run.e2e.ts: directamente a vite preview.
test.use({ baseURL: 'http://localhost:4173' })

const yaml = (key: string) => `id: ${key}
name: Revisión E2E
description: Revisa y corrige.

agents:
  reviewer:
    prompt: Revisa {{workItem.key}}.

stages:
  - id: review
    type: agent
    agent: reviewer
  - id: fix
    type: agent
    prompt: Corrige lo que encontró la revisión.
    dependsOn: [review]
`

const problemsCard = (page: Page) => page.getByRole('region', { name: 'Problemas' })

test('importar, validar mientras se escribe, publicar, editar y descartar un workflow', async ({
  page,
}) => {
  const key = `e2e-${Date.now().toString(36)}`
  await page.goto('/workflows')
  await login(page)
  await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible()
  await expectAccessible(page, 'lista de workflows')

  await test.step('importar el YAML en el alta', async () => {
    await page.getByRole('button', { name: 'Nuevo workflow' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Nuevo workflow' })).toBeVisible()
    await page.getByLabel('Archivo YAML del workflow').setInputFiles({
      name: `${key}.yaml`,
      mimeType: 'text/yaml',
      buffer: Buffer.from(yaml(key)),
    })
    await expect(page.locator('.monaco-editor').getByText(`id: ${key}`)).toBeVisible()
    await expect(problemsCard(page).getByText('Sin problemas')).toBeVisible()
    const summary = page.getByRole('region', { name: 'Qué define' })
    const stages = summary.getByRole('list').first().getByRole('listitem')
    await expect(stages).toHaveCount(2)
    await expect(stages.nth(1)).toContainText('fix agent · Agente: el del repositorio')
    await expect(stages.nth(1)).toContainText('Depende de review')
    await expectAccessible(page, 'alta de workflow')
  })

  await test.step('un error aparece mientras se escribe y desaparece al corregirlo', async () => {
    await page.locator('.monaco-editor .view-lines').click()
    await page.keyboard.press('ControlOrMeta+End')
    await page.keyboard.type('colr: azul')
    const problem = problemsCard(page).getByRole('button', { name: /Clave `colr` desconocida/ })
    await expect(problem).toBeVisible()
    await expect(problemsCard(page).getByText('1 error')).toBeVisible()
    await expect(page.locator('.monaco-editor .squiggly-error')).toHaveCount(1)
    for (let i = 0; i < 'colr: azul'.length; i++) await page.keyboard.press('Backspace')
    await expect(problemsCard(page).getByText('Sin problemas')).toBeVisible()
  })

  await test.step('crear el borrador y publicarlo', async () => {
    await page.getByRole('button', { name: 'Crear borrador' }).click()
    await page.waitForURL(`**/workflows/${key}`)
    await expect(page.getByRole('heading', { level: 1, name: 'Revisión E2E' })).toBeVisible()
    await expect(page.getByText('aún sin publicar')).toBeVisible()
    await page.getByRole('button', { name: 'Publicar…' }).click()
    const sheet = page.getByRole('dialog', { name: `Publicar ${key} v1` })
    await expectAccessible(page, 'panel de publicar')
    await sheet.getByRole('button', { name: 'Publicar v1' }).click()
    await expect(sheet).toBeHidden()
    await expect(page.getByText(/versión publicada v1 desde el/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Guardar' })).toHaveCount(0)
    await expectAccessible(page, 'workflow publicado')
  })

  await test.step('«Editar» abre el borrador de la v2, que se descarta sin tocar la v1', async () => {
    await page.getByRole('button', { name: 'Editar' }).click()
    const versions = page.getByRole('navigation', { name: 'Versiones' })
    await expect(versions.getByRole('button')).toHaveCount(2)
    await expect(page.getByRole('button', { name: 'Guardar' })).toBeDisabled()
    await page.getByRole('button', { name: 'Descartar borrador…' }).click()
    const sheet = page.getByRole('dialog', { name: 'Descartar el borrador v2' })
    await sheet.getByRole('button', { name: 'Descartar' }).click()
    await expect(versions.getByRole('button')).toHaveCount(1)
    await expect(page.getByRole('button', { name: 'Editar' })).toBeVisible()
  })

  await test.step('sale en la lista con su versión publicada', async () => {
    await page.goto('/workflows')
    await page.getByLabel('Buscar workflows').fill(key)
    const row = page.getByRole('row').filter({ hasText: key })
    await expect(row).toHaveCount(1)
    await expect(row.getByText('v1')).toBeVisible()
  })
})
