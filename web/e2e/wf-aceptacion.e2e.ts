import { expect, test, type Page } from '@playwright/test'
import { expectAccessible, login, publishWorkflowViaApi } from './helpers.ts'

/**
 * Aceptación del hito WF («Workflows que se entienden», WF-G), en escritorio y en el móvil: la
 * tarjeta del workflow, su resumen, editar un paso sin tocar el YAML y publicar la versión nueva,
 * con axe en cada pantalla.
 */

test.use({ baseURL: 'http://localhost:4173' })

const yaml = (key: string) => `id: ${key}
name: Aceptación WF
description: Revisa y corrige.

agents:
  reviewer:
    prompt: Revisa {{workItem.key}}.

stages:
  - id: review
    name: Revisa el cambio
    type: agent
    agent: reviewer
  - id: fix
    name: Corrige
    type: agent
    prompt: Corrige lo que encontró la revisión.
    dependsOn: [review]
`

const shots = process.env.E2E_OUT ?? 'build'

async function cardAndSummary(page: Page, key: string, where: string) {
  await page.goto('/workflows')
  await page.getByLabel('Buscar workflows').fill(key)
  const card = page.getByRole('listitem').filter({ hasText: 'Aceptación WF' })
  await expect(card).toHaveCount(1)
  await expectAccessible(page, `tarjetas (${where})`)
  await page.screenshot({ path: `${shots}/wf-g-tarjetas-${where}.png`, fullPage: true })

  await card.getByRole('link', { name: /Aceptación WF/ }).click()
  await page.waitForURL(`**/workflows/${key}**`)
  await expect(page.getByRole('tab', { name: 'Resumen' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('li.wf-step')).toHaveCount(2)
  await expectAccessible(page, `resumen (${where})`)
  await page.screenshot({ path: `${shots}/wf-g-resumen-${where}.png`, fullPage: true })
}

async function publish(page: Page, key: string, where: string) {
  await page.getByRole('button', { name: 'Publicar…' }).click()
  const sheet = page.getByRole('dialog', { name: `Publicar ${key} v2` })
  await expectAccessible(page, `publicar (${where})`)
  await sheet.getByRole('button', { name: 'Publicar v2' }).click()
  await expect(sheet).toBeHidden()
  await expect(page.getByText(/versión publicada v2 desde el/)).toBeVisible()
  await expect(page.locator('li.wf-step').nth(1)).toContainText('Corrige lo encontrado')
}

test('escritorio: tarjeta, resumen, editar un paso y publicar', async ({ page }) => {
  const key = `wf-acepta-${Date.now().toString(36)}`
  await page.goto('/workflows')
  await login(page)
  await publishWorkflowViaApi(page, yaml(key))
  await cardAndSummary(page, key, 'escritorio')

  await test.step('editar el nombre de un paso en su panel', async () => {
    await page.getByRole('button', { name: 'Editar' }).click()
    await expect(page.getByRole('tab', { name: 'Pasos' })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('button', { name: '2. Corrige' }).click()
    const panel = page.getByRole('region', { name: 'Fase fix' })
    await panel.getByLabel('Nombre').fill('Corrige lo encontrado')
    await panel.getByLabel('Nombre').press('Enter')
    await expect(page.locator('.definition-bar-status')).toHaveText('Sin problemas')
    await expectAccessible(page, 'editor por pasos (escritorio)')
    await page.screenshot({ path: `${shots}/wf-g-editar-escritorio.png`, fullPage: true })
    await page.getByRole('button', { name: 'Guardar' }).click()
    await expect(page.getByText(/Guardado el/)).toBeVisible()
  })

  await publish(page, key, 'escritorio')
})

test.describe('móvil', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

  test('móvil: tarjeta, resumen, editar un paso en su hoja y publicar desde abajo', async ({
    page,
  }) => {
    const key = `wf-acepta-m-${Date.now().toString(36)}`
    await page.goto('/workflows')
    await login(page)
    await publishWorkflowViaApi(page, yaml(key))
    await cardAndSummary(page, key, 'movil')

    await test.step('cada paso se abre en una hoja; Guardar y Publicar quedan abajo', async () => {
      await page.getByRole('button', { name: 'Editar' }).click()
      await expect(page.getByRole('tab', { name: 'Pasos' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      const dock = page.locator('.definition-dock')
      await expect(dock.getByRole('button', { name: 'Guardar' })).toBeVisible()
      await expectAccessible(page, 'editor por pasos (móvil)')
      await page.screenshot({ path: `${shots}/wf-g-pasos-movil.png`, fullPage: true })

      await page.getByRole('button', { name: /2\. Corrige/ }).tap()
      const sheet = page.getByRole('dialog', { name: '2. Corrige' })
      await sheet.getByLabel('Nombre').fill('Corrige lo encontrado')
      await sheet.getByLabel('Nombre').press('Enter')
      await expectAccessible(page, 'paso en su hoja (móvil)')
      await page.screenshot({ path: `${shots}/wf-g-paso-movil.png` })
      await sheet.getByRole('button', { name: 'Hecho' }).tap()
      await expect(sheet).toBeHidden()

      await page
        .getByRole('button', { name: /Añadir paso/ })
        .last()
        .tap()
      const palette = page.getByRole('dialog', { name: 'Añadir paso' })
      await expect(palette.getByRole('button', { name: /Entrega por PR/ })).toBeVisible()
      await expectAccessible(page, 'añadir paso (móvil)')
      await page.screenshot({ path: `${shots}/wf-g-anadir-movil.png` })
      await palette.getByRole('button', { name: 'Cerrar' }).tap()

      await expect(page.locator('.definition-bar-status')).toHaveText('Sin problemas')
      await dock.getByRole('button', { name: 'Guardar' }).tap()
      await expect(page.getByText(/Guardado el/)).toBeVisible()
    })

    await publish(page, key, 'movil')
  })
})
