import { expect, test, type Page } from '@playwright/test'
import { expectAccessible, login, workItemViaApi } from './helpers.ts'

/**
 * Aceptación del editor visual (W8): un workflow montado en el grafo, sin escribir YAML, se publica
 * y se ejecuta; su versión siguiente (con una fase más) también, y la primera ejecución sigue
 * enseñando su versión y su grafo de antes. Con axe en claro y oscuro.
 */

// Sin el proxy cortable de run.e2e.ts: directamente a vite preview.
test.use({ baseURL: 'http://localhost:4173' })

const problemsCard = (page: Page) => page.getByRole('region', { name: 'Problemas' })
const graph = (page: Page) => page.getByRole('group', { name: 'Grafo de Editor E2E' })
const box = (page: Page, id: string) => graph(page).locator(`.react-flow__node[data-id="${id}"]`)

/** Escribe en un campo del panel y lo aplica (Intro o, si es de varias líneas, al salir). */
async function commit(page: Page, label: string, value: string, scope = page.locator('body')) {
  const field = scope.getByLabel(label, { exact: true })
  await field.fill(value)
  await field.press('Tab')
}

async function axeInBothThemes(page: Page, what: string) {
  await expectAccessible(page, `${what} (claro)`)
  await page.emulateMedia({ colorScheme: 'dark' })
  await expectAccessible(page, `${what} (oscuro)`)
  await page.emulateMedia({ colorScheme: 'light' })
}

/** Lanza la última versión publicada desde el trabajo y espera a que termine. Devuelve su id. */
async function launchAndFinish(page: Page, workItemId: string, key: string): Promise<string> {
  await page.goto(`/work-items/${workItemId}`)
  await page.getByRole('button', { name: 'Lanzar workflow' }).click()
  const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
  await sheet.getByLabel('Workflow').selectOption(key)
  await sheet.getByLabel('Tema').fill('la suma')
  await sheet.getByRole('button', { name: 'Lanzar', exact: true }).click()
  await page.waitForURL('**/runs/**')
  await expect(page.locator('header.run-header .hero-top').getByText('Completada')).toBeVisible({
    timeout: 120_000,
  })
  return page.url().split('/runs/')[1].split('?')[0]
}

test('un workflow montado en el grafo se publica, se ejecuta y su versión siguiente no cambia la primera ejecución', async ({
  page,
}) => {
  test.setTimeout(360_000)
  const key = `editor-${Date.now().toString(36)}`
  await page.goto('/workflows/new')
  await login(page)

  await test.step('montar el workflow en el grafo, desde cero', async () => {
    await page.getByLabel('Plantilla').selectOption('desde-cero')
    await expect(page.getByRole('tab', { name: 'Grafo' })).toHaveAttribute('aria-selected', 'true')

    await page.getByRole('button', { name: 'Workflow', exact: true }).click()
    await commit(page, 'Id', key)
    await commit(page, 'Nombre', 'Editor E2E')

    await page.getByRole('button', { name: 'Entradas', exact: true }).click()
    await page.getByRole('button', { name: 'Añadir entrada' }).click()
    await commit(page, 'Nombre de dato', 'tema')
    await page.getByRole('checkbox', { name: 'Obligatoria' }).check()

    await page.getByRole('button', { name: 'Agentes', exact: true }).click()
    await page.getByRole('button', { name: 'Añadir agente' }).click()
    await commit(page, 'Nombre de agente', 'autor')
    const agents = page.getByRole('region', { name: 'Agentes' })
    await commit(page, 'Prompt', 'Escribe sobre {{inputs.tema}}.', agents)
    await commit(page, 'Turnos máximos', '10', agents)
    await page.screenshot({
      path: `${process.env.E2E_OUT ?? 'build'}/w8c-agentes.png`,
      fullPage: true,
    })

    await box(page, 'agent').getByRole('button').click()
    const stage = page.getByRole('region', { name: 'Fase agent' })
    await stage.getByLabel('Agente').selectOption('autor')
    await commit(page, 'Prompt', 'Primera versión sobre {{inputs.tema}}.', stage)
    await commit(page, 'Id', 'escribir', stage)
    await expect(page.getByRole('region', { name: 'Fase escribir' })).toBeVisible()
    await expect(problemsCard(page).getByText('Sin problemas')).toBeVisible()
    await expect(box(page, 'escribir')).toContainText('autor')
    await axeInBothThemes(page, 'editor del workflow nuevo')
  })

  await test.step('crear el borrador y publicar la v1', async () => {
    await page.getByRole('button', { name: 'Crear borrador' }).click()
    await page.waitForURL(`**/workflows/${key}**`)
    await page.getByRole('button', { name: 'Publicar…' }).click()
    const sheet = page.getByRole('dialog', { name: `Publicar ${key} v1` })
    await sheet.getByRole('button', { name: 'Publicar v1' }).click()
    await expect(page.getByText(/versión publicada v1 desde el/)).toBeVisible()
  })

  const { workItemId } = await workItemViaApi(page)
  const first = await test.step('ejecutar la v1', () => launchAndFinish(page, workItemId, key))

  await test.step('añadir una fase en la v2 desde el grafo y publicarla', async () => {
    await page.goto(`/workflows/${key}`)
    await page.getByRole('button', { name: 'Editar' }).click()
    await expect(page.getByRole('button', { name: 'Guardar' })).toBeDisabled()
    await page.getByRole('tab', { name: 'Grafo' }).click()
    await box(page, 'escribir').getByRole('button').click()
    await page.getByLabel('Añadir fase').selectOption('agent')
    const stage = page.getByRole('region', { name: 'Fase agent' })
    await commit(page, 'Prompt', 'Repasa lo escrito sobre {{inputs.tema}}.', stage)
    await commit(page, 'Id', 'repasar', stage)
    await expect(problemsCard(page).getByText('Sin problemas')).toBeVisible()
    await axeInBothThemes(page, 'editor del borrador v2')
    await page.getByRole('button', { name: 'Guardar' }).click()
    await expect(page.getByText(/Guardado el/)).toBeVisible()
    await page.getByRole('button', { name: 'Publicar…' }).click()
    const sheet = page.getByRole('dialog', { name: `Publicar ${key} v2` })
    await sheet.getByRole('button', { name: 'Publicar v2' }).click()
    await expect(page.getByText(/versión publicada v2 desde el/)).toBeVisible()
  })

  const second = await test.step('ejecutar la v2', () => launchAndFinish(page, workItemId, key))

  const runGraph = page
    .getByRole('navigation', { name: 'Fases y agentes' })
    .getByRole('group', { name: 'Fases de la ejecución' })
  const chips = page.getByRole('list', { name: 'Detalles de la ejecución' })

  await test.step('la v2 ejecutó sus dos fases', async () => {
    await page.goto(`/runs/${second}`)
    await expect(chips).toContainText('Editor E2E v2')
    await expect(runGraph.locator('.graph-node')).toHaveCount(2)
    await expect(page.getByText('2 de 2 fases completadas')).toBeVisible()
  })

  await test.step('la primera ejecución sigue con su versión y su grafo de antes', async () => {
    await page.goto(`/runs/${first}`)
    await expect(chips).toContainText('Editor E2E v1')
    // Con una sola fase, el progreso no dibuja el grafo: lo dice.
    await expect(page.getByText('1 de 1 fase completada')).toBeVisible()
    const run = await (await page.request.get(`/api/workflow-runs/${first}`)).json()
    expect(run.stages.map((s: { stageKey: string }) => s.stageKey)).toEqual(['escribir'])
    await expectAccessible(page, 'primera ejecución')
  })
})
