import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
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
  // Lanzamiento completo y una reanudación, las dos con fake-claude a velocidad lenta.
  test.setTimeout(240_000)
  const key = `E${Date.now().toString(36).toUpperCase().slice(-6)}`

  await test.step('crear proyecto', async () => {
    await page.goto('/projects')
    await page.getByLabel('Clave').fill(key)
    await page.getByLabel('Nombre').fill('E2E')
    await page.getByRole('button', { name: 'Crear proyecto' }).click()
    await page.getByRole('link', { name: new RegExp(key) }).click()
    // Sin esperar a la página del proyecto, «Nombre» puede resolverse aún en el formulario de
    // proyectos y el repositorio se envía sin nombre.
    await expect(page.getByRole('heading', { level: 1, name: new RegExp(key) })).toBeVisible()
  })

  await test.step('registrar repositorio y crear trabajo', async () => {
    await page.getByLabel('Nombre', { exact: true }).fill('demo')
    await page.getByLabel('Ruta local (en la máquina del runner)').fill(repoPath)
    await page.getByLabel('Comando de verificación (opcional)').fill('sh check.sh')
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
    await page.getByRole('tab', { name: 'Conversación' }).click()
    await expect(page.getByRole('tabpanel').getByText(/Fixed `add\(\)`/)).toBeVisible()

    await page.getByRole('tab', { name: 'Herramientas' }).click()
    const tools = page.getByRole('tabpanel').locator('.tool-calls > li')
    await expect(tools).toHaveCount(3)
    await expect(page.getByRole('tabpanel').getByText('Hecha')).toHaveCount(3)
  })

  await test.step('artefactos: archivo modificado, su diff y el commit', async () => {
    await page.getByRole('tab', { name: 'Artefactos' }).click()
    const panel = page.getByRole('tabpanel')
    await expect(
      panel.getByRole('list', { name: 'Archivos modificados' }).getByRole('button', {
        name: /calc\.py/,
      }),
    ).toBeVisible({ timeout: 30_000 })
    await expect(panel.getByLabel('Diff de calc.py')).toContainText('return a + b', {
      timeout: 30_000,
    })
    await expect(panel.getByText('fix add')).toBeVisible()
  })

  await test.step('verificación independiente y reejecución', async () => {
    await page.getByRole('tab', { name: 'Verificación' }).click()
    const panel = page.getByRole('tabpanel')
    const verified = panel.getByRole('region', { name: 'Verificado por Skynet' })
    await expect(verified.getByText('Pasa')).toBeVisible({ timeout: 60_000 })
    await expect(verified.getByText('1 tests · 0 fallidos · 0 omitidos')).toBeVisible()
    await expect(verified.getByText('sh check.sh')).toBeVisible()
    await expect(header.getByText('1/1 tests')).toBeVisible()

    await panel.getByRole('button', { name: 'Reejecutar verificación' }).click()
    await expect(panel.getByRole('heading', { name: 'Anteriores' })).toBeVisible({
      timeout: 30_000,
    })
    await expect(verified.getByText('Manual')).toBeVisible({ timeout: 60_000 })
    await expect(verified.getByText('Pasa')).toBeVisible({ timeout: 60_000 })
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

  await test.step('enviar un mensaje continúa la conversación en otra invocación', async () => {
    const firstRun = page.url()
    await page.getByRole('tab', { name: 'Conversación' }).click()
    const panel = page.getByRole('tabpanel')
    await expect(panel.getByText('La función add de calc.py resta: arréglala')).toBeVisible()
    await expect(panel.getByText(/Continúa la sesión en el worktree/)).toBeVisible()
    await panel.getByLabel('Mensaje').fill('Añade también subtract')
    await panel.getByRole('button', { name: 'Enviar' }).click()

    // La web lleva a la ejecución nueva, que muestra la conversación completa.
    await page.waitForURL((url) => url.toString() !== firstRun)
    await expect(panel.getByText('Reanudación')).toBeVisible()
    await expect(panel.getByText('La función add de calc.py resta: arréglala')).toBeVisible()
    await expect(panel.getByText('Añade también subtract')).toBeVisible()
    await expect(panel.getByText(/subtract\(a, b\)/).first()).toBeVisible({ timeout: 90_000 })
    await expect(header.getByRole('heading', { level: 1 }).getByText('Completada')).toBeVisible({
      timeout: 30_000,
    })
    await expect(header.getByText(/US\$/)).toBeVisible()
  })
})

/** Procesos de fake-claude vivos en esta máquina. */
function fakeClaudeProcesses(): string[] {
  try {
    return execFileSync('pgrep', ['-fa', 'dev[.]skynet[.]fakeclaude'], { encoding: 'utf8' })
      .split('\n')
      .filter(Boolean)
  } catch {
    return [] // pgrep sale con 1 si no encuentra ninguno.
  }
}

async function launchViaApi(page: Page, prompt: string): Promise<string> {
  const key = `C${Date.now().toString(36).toUpperCase().slice(-6)}`
  const post = async (path: string, data: unknown) =>
    (await page.request.post(path, { data })).json()
  const project = await post('/api/projects', { key, name: 'Cancelar' })
  const repo = await post(`/api/projects/${project.id}/repositories`, {
    name: 'demo',
    localPath: repoPath,
  })
  const item = await post(`/api/projects/${project.id}/work-items`, {
    title: 'Cancelar a mitad',
    type: 'BUG',
  })
  const run = await post(`/api/work-items/${item.id}/runs`, { repositoryId: repo.id, prompt })
  return run.id
}

test('cancelar a mitad deja el agente cancelado y ningún proceso vivo', async ({ page }) => {
  const runId = await launchViaApi(page, 'Tarea larga')
  await page.goto(`/runs/${runId}`)
  const header = page.locator('header.run-header')

  await expect(
    page
      .getByRole('region', { name: 'Timeline' })
      .getByRole('button', { name: /Read: / })
      .first(),
  ).toBeVisible({ timeout: 45_000 })
  expect(fakeClaudeProcesses()).not.toHaveLength(0)

  await header.getByRole('button', { name: 'Cancelar agente' }).click()
  await header.getByRole('button', { name: 'Sí, cancelar' }).click()

  await expect(header.getByRole('heading', { level: 1 }).getByText('Cancelada')).toBeVisible({
    timeout: 30_000,
  })
  await expect.poll(fakeClaudeProcesses, { timeout: 10_000 }).toHaveLength(0)
})
