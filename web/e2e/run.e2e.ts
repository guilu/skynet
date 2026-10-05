import { expect, test } from '@playwright/test'
import { startCuttableProxy } from './cuttableProxy.ts'

/**
 * Recorrido completo (issue #6): crear proyecto, repositorio y trabajo → lanzar un agente → ver
 * herramientas y mensajes en vivo → cortar la conexión y reconectar → estado final con coste.
 *
 * El runner usa fake-claude con la grabación `02-tools` (Read, Edit, Bash y un mensaje final) y un
 * retardo entre líneas, para que haya tiempo de cortar la conexión a mitad de ejecución.
 */
const repoPath = process.env.E2E_REPO_PATH ?? '/tmp/skynet-e2e-repo'

let network: Awaited<ReturnType<typeof startCuttableProxy>>
test.beforeAll(async () => {
  network = await startCuttableProxy(4100, { host: 'localhost', port: 4173 })
})
test.afterAll(async () => {
  await network.close()
})

test('crear, lanzar, seguir en vivo, reconectar y ver el resultado', async ({ page }) => {
  const key = `E${Date.now().toString(36).toUpperCase().slice(-6)}`

  await test.step('crear proyecto', async () => {
    await page.goto('/projects')
    await page.getByLabel('Clave').fill(key)
    await page.getByLabel('Nombre').fill('E2E')
    await page.getByRole('button', { name: 'Crear proyecto' }).click()
    await page.getByRole('link', { name: new RegExp(key) }).click()
  })

  await test.step('registrar repositorio y crear trabajo', async () => {
    await page.getByLabel('Nombre', { exact: true }).fill('demo')
    await page.getByLabel('Ruta local (en la máquina del runner)').fill(repoPath)
    await page.getByRole('button', { name: 'Registrar repositorio' }).click()
    await expect(page.getByText(repoPath)).toBeVisible()

    await page.getByLabel('Título').fill('Arreglar la suma')
    await page.getByRole('button', { name: 'Crear trabajo' }).click()
    await page.getByRole('link', { name: `${key}-1 Arreglar la suma` }).click()
  })

  await test.step('lanzar con límites', async () => {
    await page.getByLabel('Prompt').fill('La función add de calc.py resta: arréglala')
    await page.getByLabel('Turnos máximos').fill('5')
    await page.getByRole('button', { name: 'Lanzar' }).click()
    await expect(page.getByRole('heading', { level: 1, name: /Arreglar la suma/ })).toBeVisible()
  })

  const header = page.locator('header.run-header')
  const timeline = page.getByRole('region', { name: 'Timeline' })

  await test.step('herramientas en vivo', async () => {
    await expect(header.getByText('En vivo')).toBeVisible()
    await expect(timeline.getByRole('button', { name: /Read: / }).first()).toBeVisible({
      timeout: 45_000,
    })
  })

  await test.step('cortar y reconectar el stream', async () => {
    network.cut()
    await expect(header.getByText('Reconectando…')).toBeVisible()
    await page.waitForTimeout(3000)
    network.restore()
    await expect(header.getByText('En vivo')).toBeVisible({ timeout: 20_000 })
  })

  await test.step('estado final con coste', async () => {
    await expect(header.getByText('Completada')).toBeVisible({ timeout: 90_000 })
    await expect(header.getByText(/US\$/)).toBeVisible()
    await expect(header.getByRole('button', { name: 'Cancelar agente' })).toHaveCount(0)
  })

  await test.step('mensajes y herramientas en el inspector', async () => {
    await page.getByRole('tab', { name: 'Mensajes' }).click()
    await expect(page.getByRole('tabpanel').getByText(/Fixed `add\(\)`/)).toBeVisible()

    await page.getByRole('tab', { name: 'Herramientas' }).click()
    const tools = page.getByRole('tabpanel').locator('.tool-calls > li')
    await expect(tools).toHaveCount(3)
    await expect(page.getByRole('tabpanel').getByText('Hecha')).toHaveCount(3)
  })

  await test.step('el timeline no pierde ni repite eventos tras reconectar', async () => {
    const runId = new URL(page.url()).pathname.split('/').at(-1)!
    const stored = await (
      await page.request.get(`/api/events?workflowRunId=${runId}&limit=1000`)
    ).json()
    await page.getByLabel('Tipo').selectOption('message')
    await expect(timeline.locator('.timeline-item')).toHaveCount(
      stored.filter((e: { type: string }) => e.type === 'agent.message.received').length,
    )
    await page.getByLabel('Tipo').selectOption('tool')
    await expect(timeline.locator('.timeline-item')).toHaveCount(3)
  })
})
