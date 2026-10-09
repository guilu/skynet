import { expect, test, type Page } from '@playwright/test'
import { csrf, expectAccessible, launchViaApi, login } from './helpers.ts'

/**
 * Revisión de la web (M6-D): accesibilidad con axe en las páginas principales y ningún secreto en
 * el DOM. El repositorio de prueba de `scripts/e2e.sh` lleva un token en `calc.py` (sale en el
 * contexto del diff) y su `check.sh` imprime otro (sale en el log de la verificación); los dos
 * tienen que llegar a la página ya ocultos.
 */

// Sin el proxy cortable de run.e2e.ts: directamente a vite preview.
test.use({ baseURL: 'http://localhost:4173' })

const SECRETS = [process.env.E2E_SECRET_GITHUB, process.env.E2E_SECRET_AWS].filter(
  (s): s is string => !!s,
)

/** Lanza un agente con verificación y espera a que termine y a que la verificación pase. */
async function completedRun(page: Page): Promise<string> {
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const runId = await launchViaApi(page, 'Arregla add() en calc.py', {
    validationCommand: 'sh check.sh',
  })
  await page.goto(`/runs/${runId}`)
  const header = page.locator('header.run-header')
  await expect(header.getByRole('heading', { level: 1 }).getByText('Completada')).toBeVisible({
    timeout: 90_000,
  })
  await page.getByRole('tab', { name: 'Verificación' }).click()
  await expect(
    page.getByRole('region', { name: 'Verificado por Skynet' }).getByText('Pasa'),
  ).toBeVisible({ timeout: 60_000 })
  return runId
}

/** Ningún secreto en el HTML ni en el texto de la página tal como está ahora. */
async function expectNoSecrets(page: Page, where: string) {
  const html = await page.content()
  for (const secret of SECRETS) {
    expect(html, `${where}: el secreto aparece en el DOM`).not.toContain(secret)
  }
}

test('ningún secreto llega al DOM', async ({ page }) => {
  test.setTimeout(240_000)
  expect(SECRETS, 'scripts/e2e.sh define E2E_SECRET_GITHUB y E2E_SECRET_AWS').toHaveLength(2)
  const runId = await completedRun(page)
  const panel = page.getByRole('region', { name: 'Inspector del agente' }).getByRole('tabpanel')

  await expectNoSecrets(page, 'Verificación')
  for (const tab of ['Resumen', 'Prompt', 'Conversación', 'Herramientas']) {
    await page.getByRole('tab', { name: tab }).click()
    await expectNoSecrets(page, tab)
  }

  await page.getByRole('tab', { name: 'Artefactos' }).click()
  // El diff de calc.py lleva la línea del token como contexto.
  // Se busca el texto pintado y no el elemento con la etiqueta: mientras Monaco se descarga, la
  // etiqueta está en un bloque de texto, y después en el campo de edición de Monaco, que va vacío.
  await expect(panel.getByText(/return\s+a\s+\+\s+b/).first()).toBeVisible({ timeout: 30_000 })
  // Monaco pinta los espacios como espacios duros: entre palabras va `\s+`, no un espacio.
  await expect(panel.getByText(/token\s+de\s+pruebas:\s+\[REDACTED\]/).first()).toBeVisible()
  await expectNoSecrets(page, 'diff')
  // La salida de la verificación imprime el otro token.
  await panel.getByRole('button', { name: 'Salida de la verificación' }).first().click()
  await expect(panel.getByText('[REDACTED]').first()).toBeVisible({ timeout: 30_000 })
  await expectNoSecrets(page, 'log de la verificación')

  // El timeline (en la misma página, sin filtros) ya estaba en todas las comprobaciones.

  // Y la API de la que bebe la web tampoco los devuelve.
  const events = await (
    await page.request.get(`/api/events?workflowRunId=${runId}&limit=1000`)
  ).text()
  for (const secret of SECRETS) expect(events).not.toContain(secret)
})

test('las páginas principales pasan axe', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
  await expectAccessible(page, 'login')

  const runId = await completedRun(page)
  await expectAccessible(page, 'ejecución · Verificación')
  for (const tab of ['Resumen', 'Prompt', 'Herramientas', 'Coste', 'Artefactos']) {
    await page.getByRole('tab', { name: tab }).click()
    await expect(page.getByRole('tabpanel', { name: tab })).toBeVisible()
    await expectAccessible(page, `ejecución · ${tab}`)
  }
  // Las tres vistas de la actividad, con una herramienta abierta en la conversación.
  for (const view of ['Cascada', 'Eventos', 'Conversación']) {
    await page.getByRole('tab', { name: new RegExp(`^${view}`) }).click()
    await expect(page.getByRole('tabpanel', { name: new RegExp(`^${view}`) })).toBeVisible()
    await expectAccessible(page, `ejecución · ${view}`)
  }
  await page.locator('.tool-card summary').first().click()
  await expectAccessible(page, 'ejecución · herramienta en la conversación')

  const pages: [string, string][] = [
    ['/', 'Dashboard'],
    ['/runs', 'Ejecuciones'],
    ['/projects', 'Proyectos'],
    ['/runners', 'Runners'],
    ['/activity', 'Actividad'],
    ['/workflows', 'Workflows'],
    ['/settings', 'Ajustes'],
    ['/no-existe', 'Página no encontrada'],
  ]
  for (const [path, name] of pages) {
    await page.goto(path)
    await expect(
      page.getByRole('heading', { level: 1, name: new RegExp(`^${name}`) }),
    ).toBeVisible()
    await expectAccessible(page, name)
  }

  // Paleta ⌘K y menú de tema de la barra superior, abiertos.
  await page.keyboard.press('ControlOrMeta+k')
  await expect(page.getByRole('dialog', { name: 'Buscar o ejecutar una orden' })).toBeVisible()
  await expect(page.getByRole('option').first()).toBeVisible()
  await expectAccessible(page, 'paleta ⌘K')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: /^Tema:/ }).click()
  await expect(page.getByRole('menu')).toBeVisible()
  await expectAccessible(page, 'menú de tema')
  await page.keyboard.press('Escape')

  // Proyecto y trabajo de la ejecución: se llega desde la lista de proyectos.
  await page.goto('/projects')
  await page
    .getByRole('link', { name: /E2E por API/ })
    .first()
    .click()
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expectAccessible(page, 'proyecto')
  await page.getByRole('tab', { name: /Repositorios/ }).click()
  await expect(page.getByRole('button', { name: /^Editar política de / }).first()).toBeVisible()
  await expectAccessible(page, 'repositorios del proyecto')
  await page
    .getByRole('button', { name: /^Editar política de / })
    .first()
    .click()
  await expect(page.getByRole('dialog', { name: /^Política de agentes de / })).toBeVisible()
  await expectAccessible(page, 'panel de la política')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Nuevo trabajo' }).click()
  await expect(page.getByRole('dialog', { name: 'Nuevo trabajo' })).toBeVisible()
  await expectAccessible(page, 'panel de nuevo trabajo')
  await page.keyboard.press('Escape')

  await page.goto(`/runs/${runId}`)
  await page.locator('header.run-header').getByRole('link').first().click()
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expectAccessible(page, 'trabajo')
  await page.getByRole('button', { name: 'Lanzar agente' }).click()
  await expect(page.getByRole('dialog', { name: 'Lanzar agente' })).toBeVisible()
  await expectAccessible(page, 'panel de lanzar')
  await page.keyboard.press('Escape')
})

test('en tema oscuro las páginas también pasan axe', async ({ page }) => {
  // Los tokens del tema oscuro tienen su propio contraste: se revisan aparte, con el tema del
  // sistema en oscuro (el de la web por defecto).
  test.setTimeout(180_000)
  await page.emulateMedia({ colorScheme: 'dark' })
  const runId = await completedRun(page)
  await expectAccessible(page, 'oscuro · ejecución')
  for (const tab of ['Artefactos', 'Resumen']) {
    await page.getByRole('tab', { name: tab }).click()
    await expect(page.getByRole('tabpanel', { name: tab })).toBeVisible()
    await expectAccessible(page, `oscuro · ejecución · ${tab}`)
  }
  await page.getByRole('tab', { name: /^Cascada/ }).click()
  await expectAccessible(page, 'oscuro · cascada')

  for (const [path, name] of [
    ['/', 'Dashboard'],
    ['/runs', 'Ejecuciones'],
    ['/projects', 'Proyectos'],
    ['/runners', 'Runners'],
    ['/activity', 'Actividad'],
    ['/workflows', 'Workflows'],
    ['/settings', 'Ajustes'],
    ['/no-existe', 'Página no encontrada'],
  ]) {
    await page.goto(path)
    await expect(
      page.getByRole('heading', { level: 1, name: new RegExp(`^${name}`) }),
    ).toBeVisible()
    await expectAccessible(page, `oscuro · ${name}`)
  }

  await page.goto(`/runs/${runId}`)
  await page.locator('header.run-header').getByRole('link').first().click()
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expectAccessible(page, 'oscuro · trabajo')
  await page.getByRole('button', { name: 'Lanzar agente' }).click()
  await expect(page.getByRole('dialog', { name: 'Lanzar agente' })).toBeVisible()
  await expectAccessible(page, 'oscuro · panel de lanzar')
})

test('una paleta personalizada se guarda, se aplica a toda la web y pasa axe', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/settings')
  await login(page)
  await expect(page.getByRole('heading', { level: 1, name: 'Ajustes' })).toBeVisible()
  try {
    await page.getByText('Frambuesa').click()
    await page.getByLabel('En curso').fill('#2f9bd6')
    await page.getByRole('button', { name: 'Guardar' }).click()
    await expect(page.getByText('Guardado.')).toBeVisible()

    // Tras recargar (y en el login, que lee la paleta sin sesión) sigue aplicada.
    await page.reload()
    const primary = () =>
      page.evaluate<string>(
        `getComputedStyle(document.documentElement).getPropertyValue('--primary').trim()`,
      )
    await expect.poll(primary).toBe('#c2255c')
    await expect(page.getByRole('radio', { name: 'Frambuesa' })).not.toBeChecked()
    await expectAccessible(page, 'ajustes con paleta propia')

    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: /^Dashboard/ })).toBeVisible()
    await expectAccessible(page, 'dashboard con paleta propia')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expect.poll(primary).not.toBe('#c2255c')
    await expectAccessible(page, 'oscuro · dashboard con paleta propia')
  } finally {
    // La paleta es de todo el servidor: se deja la de Skynet para el resto de tests.
    await page.request.put('/api/settings/appearance', {
      data: { colors: {} },
      headers: await csrf(page),
    })
  }
})
