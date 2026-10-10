import { expect, test, type Page } from '@playwright/test'
import { startCuttableProxy } from './cuttableProxy.ts'
import { fakeClaudeProcesses, launchViaApi, login, repoPath, service } from './helpers.ts'

/**
 * Recorrido completo (issue #6): crear proyecto, repositorio y trabajo → lanzar un agente → ver
 * herramientas y mensajes en vivo → cortar la conexión y reconectar → estado final con coste.
 *
 * El runner usa fake-claude con la grabación `02-tools` (Read, Edit, Bash y un mensaje final) y un
 * retardo entre líneas, para que haya tiempo de cortar la conexión a mitad de ejecución.
 */
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

  await test.step('entrar y crear proyecto', async () => {
    // Sin sesión, la web pide entrar y después sigue en la página pedida.
    await page.goto('/projects')
    await login(page)
    await expect(page.getByRole('heading', { level: 1, name: 'Proyectos' })).toBeVisible()
    await page.getByLabel('Clave').fill(key)
    await page.getByLabel('Nombre').fill('E2E')
    await page.getByRole('button', { name: 'Crear proyecto' }).click()
    await page.getByRole('link', { name: new RegExp(key) }).click()
    // Sin esperar a la página del proyecto, «Nombre» puede resolverse aún en el formulario de
    // proyectos y el repositorio se envía sin nombre.
    await expect(page.getByRole('heading', { level: 1, name: new RegExp(key) })).toBeVisible()
  })

  await test.step('registrar repositorio y crear trabajo', async () => {
    await page.getByRole('button', { name: 'Nuevo repositorio' }).click()
    const repoSheet = page.getByRole('dialog', { name: 'Registrar repositorio' })
    await repoSheet.getByLabel('Nombre', { exact: true }).fill('demo')
    await repoSheet.getByLabel('Ruta local (en la máquina del runner)').fill(repoPath)
    await repoSheet.getByLabel('Comando de verificación (opcional)').fill('sh check.sh')
    await repoSheet.getByRole('button', { name: 'Registrar repositorio' }).click()
    await expect(repoSheet).toBeHidden()
    await expect(page.getByRole('tab', { name: /Repositorios/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await expect(page.getByText(repoPath)).toBeVisible()

    await page.getByRole('button', { name: 'Nuevo trabajo' }).click()
    const workSheet = page.getByRole('dialog', { name: 'Nuevo trabajo' })
    await workSheet.getByLabel('Título').fill('Arreglar la suma')
    await workSheet.getByRole('button', { name: 'Crear trabajo' }).click()
    await expect(workSheet).toBeHidden()
    await page.getByRole('link', { name: `${key}-1 Arreglar la suma` }).click()
  })

  await test.step('lanzar con límites', async () => {
    await page.getByRole('button', { name: 'Lanzar workflow' }).click()
    const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
    await sheet.getByLabel('Prompt').fill('La función add de calc.py resta: arréglala')
    await sheet.getByLabel('Turnos máximos').fill('5')
    await sheet.getByRole('button', { name: 'Lanzar', exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: /Arreglar la suma/ })).toBeVisible()
  })

  const header = page.locator('header.run-header')
  const timeline = page.getByRole('tabpanel', { name: /^Eventos/ })
  const inspector = page.getByRole('region', { name: 'Inspector del agente' }).getByRole('tabpanel')

  await test.step('herramientas en vivo, en la cascada y en los eventos', async () => {
    await expect(header.getByText('En vivo')).toBeVisible()
    await page.getByRole('tab', { name: 'Cascada' }).click()
    const waterfall = page.getByRole('tabpanel', { name: 'Cascada' })
    await expect(waterfall.getByRole('button', { name: /^Read / }).first()).toBeVisible({
      timeout: 45_000,
    })
    await page.getByRole('tab', { name: /^Eventos/ }).click()
    await expect(timeline.getByRole('button', { name: /Read: / }).first()).toBeVisible()
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

  await test.step('mensajes en la conversación y herramientas en el inspector', async () => {
    await page.getByRole('tab', { name: 'Conversación' }).click()
    const conversation = page.getByRole('tabpanel', { name: 'Conversación' })
    await expect(conversation.getByText(/Fixed `add\(\)`/)).toBeVisible()
    await expect(conversation.locator('.tool-card')).toHaveCount(3)

    await page.getByRole('tab', { name: 'Herramientas' }).click()
    await expect(inspector.locator('.tool-calls > li')).toHaveCount(3)
    await expect(inspector.getByText('Hecha')).toHaveCount(3)

    // Elegir una herramienta en el árbol la abre en el inspector.
    const tree = page.getByRole('navigation', { name: 'Fases y agentes' })
    await tree.getByRole('button', { name: /^Edit / }).click()
    await expect(inspector.locator('.tool-calls > li.selected > details')).toHaveAttribute(
      'open',
      '',
    )
  })

  await test.step('artefactos: archivo modificado, su diff y el commit', async () => {
    await page.getByRole('tab', { name: 'Artefactos' }).click()
    const panel = inspector
    await expect(
      panel.getByRole('list', { name: 'Archivos modificados' }).getByRole('button', {
        name: /calc\.py/,
      }),
    ).toBeVisible({ timeout: 30_000 })
    // El texto visible del diff: las líneas que pinta Monaco o, mientras carga, el bloque de texto.
    // La etiqueta está en la entrada del editor, que con EditContext no lleva el texto.
    await expect(panel.locator('.monaco-view .view-lines, pre.code-fallback')).toContainText(
      'return a + b',
      {
        timeout: 30_000,
      },
    )
    await expect(panel.getByText('fix add')).toBeVisible()
  })

  await test.step('verificación independiente y reejecución', async () => {
    await page.getByRole('tab', { name: 'Verificación' }).click()
    const panel = inspector
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
    await page.getByRole('tab', { name: /^Eventos/ }).click()
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
    const panel = page.getByRole('tabpanel', { name: 'Conversación' })
    await expect(panel.getByText('La función add de calc.py resta: arréglala')).toBeVisible()
    await expect(panel.getByText(/Continúa la sesión en el worktree/)).toBeVisible()
    await panel.getByLabel('Mensaje').fill('Añade también subtract')
    await panel.getByRole('button', { name: 'Enviar' }).click()

    // La web lleva a la ejecución nueva, que muestra la conversación completa.
    await page.waitForURL((url) => url.toString() !== firstRun)
    await expect(panel.getByText('Reanudación')).toBeVisible()
    await expect(panel.getByText('La función add de calc.py resta: arréglala')).toBeVisible()
    await expect(panel.getByText('Añade también subtract')).toBeVisible()
    // En un mensaje del agente: las herramientas plegadas también lo llevan, pero ocultas.
    await expect(
      panel
        .locator('.bubble')
        .getByText(/subtract\(a, b\)/)
        .first(),
    ).toBeVisible({
      timeout: 90_000,
    })
    await expect(header.getByRole('heading', { level: 1 }).getByText('Completada')).toBeVisible({
      timeout: 30_000,
    })
    await expect(header.getByText(/US\$/)).toBeVisible()
  })
})

test('cancelar a mitad deja el agente cancelado y ningún proceso vivo', async ({ page }) => {
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const runId = await launchViaApi(page, 'Tarea larga')
  await page.goto(`/runs/${runId}?view=events`)
  const header = page.locator('header.run-header')

  await expect(
    page
      .getByRole('tabpanel', { name: /^Eventos/ })
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

/** Abre la ejecución y espera a que el agente esté usando herramientas. */
async function openWhileRunning(page: Page, runId: string) {
  await page.goto(`/runs/${runId}?view=events`)
  await expect(
    page
      .getByRole('tabpanel', { name: /^Eventos/ })
      .getByRole('button', { name: /Read: / })
      .first(),
  ).toBeVisible({ timeout: 45_000 })
}

test('reiniciar el control plane a mitad no interrumpe la ejecución', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const runId = await launchViaApi(page, 'Sigue aunque se reinicie el control plane')
  await openWhileRunning(page, runId)

  try {
    service('stop', 'control-plane')
  } finally {
    service('start', 'control-plane')
  }

  // Las sesiones de la web eran del proceso anterior: hay que volver a entrar.
  await page.goto(`/runs/${runId}`)
  await login(page)
  const header = page.locator('header.run-header')
  await expect(header.getByRole('heading', { level: 1 }).getByText('Completada')).toBeVisible({
    timeout: 90_000,
  })
  // El runner siguió declarando la invocación en sus latidos: no se dio por perdida.
  await expect(header.getByText('Fallida')).toHaveCount(0)
})

test('reiniciar el runner a mitad deja el agente fallido y ningún proceso vivo', async ({
  page,
}) => {
  test.setTimeout(180_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const runId = await launchViaApi(page, 'Se corta la luz del runner')
  await openWhileRunning(page, runId)
  expect(fakeClaudeProcesses()).not.toHaveLength(0)

  // Un corte brusco: el runner no llega a terminar el proceso del agente.
  try {
    service('kill', 'runner')
  } finally {
    service('start', 'runner')
  }

  const header = page.locator('header.run-header')
  await expect(header.getByRole('heading', { level: 1 }).getByText('Fallida')).toBeVisible({
    timeout: 60_000,
  })
  await expect(page.getByText('El runner se reinició durante la ejecución').first()).toBeVisible()
  await expect.poll(fakeClaudeProcesses, { timeout: 10_000 }).toHaveLength(0)
})

test('login: una contraseña incorrecta no entra, salir cierra la sesión', async ({ page }) => {
  await page.goto('/')
  await login(page, 'incorrecta')
  await expect(page.getByRole('alert')).toHaveText('Usuario o contraseña incorrectos')

  await login(page)
  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
  expect((await page.request.get('/api/projects')).status()).toBe(200)

  await page.getByRole('button', { name: /^Cuenta:/ }).click()
  await page.getByRole('menuitem', { name: 'Salir' }).click()
  await expect(page.getByLabel('Usuario')).toBeVisible()
  expect((await page.request.get('/api/projects')).status()).toBe(401)
  // Sin el token CSRF, una sesión no puede cambiar nada aunque sea válida.
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const forged = await page.request.post('/api/projects', { data: { key: 'XSRF', name: 'x' } })
  expect(forged.status()).toBe(403)
})
