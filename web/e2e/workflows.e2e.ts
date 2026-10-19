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

test('la pestaña Grafo dibuja la plantilla «Revisión en paralelo»', async ({ page }) => {
  await page.goto('/workflows/new')
  await login(page)
  await page.getByLabel('Plantilla').selectOption({ label: 'Revisión en paralelo' })
  await expect(problemsCard(page).getByText('Sin problemas')).toBeVisible()
  await page.getByRole('tab', { name: 'Grafo' }).click()
  await expect(page).toHaveURL(/view=graph/)

  const graph = page.getByRole('group', { name: 'Grafo de Revisión en paralelo' })
  await expect(graph.locator('.graph-node:not(.graph-child)')).toHaveCount(5)
  // La parallel es una caja con sus dos revisores dentro.
  const review = graph.locator('.graph-group')
  await expect(review).toContainText('2 a la vez')
  // React Flow pinta los hijos al lado de su grupo en el DOM, no dentro.
  await expect(graph.locator('.graph-child')).toHaveText([/calidad/, /seguridad/])
  // implement→tests→review→fix y review→tests-finales, más la opcional fix?→tests-finales.
  await expect(graph.locator('.react-flow__edge')).toHaveCount(5)
  await expect(graph.locator('.graph-edge-optional')).toHaveCount(1)
  await expect(graph.getByText('developer · si review.hasBlockingFindings')).toBeVisible()
  // W7-C: un carril por macrofase; fix y tests-finales, sin `phase`, heredan el de review.
  await expect(graph.locator('.graph-lane')).toHaveText(['Implementación', 'Tests', 'Revisión'])
  await expectAccessible(page, 'grafo del workflow')
  await graph.screenshot({ path: `${process.env.E2E_OUT ?? 'build'}/w7a-grafo.png` })

  // El YAML sigue donde estaba.
  await page.getByRole('tab', { name: 'YAML' }).click()
  await expect(page.locator('.monaco-editor').getByText('id: revision-en-paralelo')).toBeVisible()
  await expect(graph).toHaveCount(0)
})

test('el grafo marca los problemas de cada fase, las dependencias que no existen y los ciclos', async ({
  page,
}) => {
  await page.goto('/workflows/new')
  await login(page)
  await page.getByLabel('Archivo YAML del workflow').setInputFiles({
    name: 'roto.yaml',
    mimeType: 'text/yaml',
    buffer: Buffer.from(`id: grafo-roto
name: Grafo roto

stages:
  - id: plan
    type: agent
    prompt: Planifica
    dependsOn: [spec]
  - id: build
    type: agent
    prompt: Implementa
    dependsOn: [plan, review]
  - id: review
    type: agent
    prompt: Revisa
    dependsOn: [build]
`),
  })
  await expect(problemsCard(page).getByText(/errores/)).toBeVisible()
  await page.getByRole('tab', { name: 'Grafo' }).click()

  const graph = page.getByRole('group', { name: 'Grafo de Grafo roto' })
  // `spec` no existe: sale como fase fantasma, con su flecha discontinua en rojo.
  const ghost = graph.locator('.graph-ghost')
  await expect(ghost).toContainText('spec')
  await expect(ghost).toContainText('No existe')
  await expect(graph.locator('.graph-edge-missing')).toHaveCount(1)
  // build → review → build es un ciclo: sus dos flechas en rojo.
  await expect(graph.locator('.graph-edge-cycle')).toHaveCount(2)
  await expect(graph.locator('.react-flow__node[data-id="plan"]')).toContainText('1 error')
  await expect(graph.locator('.react-flow__node[data-id="plan"]')).toHaveClass(/graph-has-errors/)
  // El control plane cuenta el ciclo en build; review, que también lo forma, lleva su insignia.
  for (const id of ['build', 'review']) {
    const node = graph.locator(`.react-flow__node[data-id="${id}"]`)
    await expect(node.locator('.graph-problems')).toHaveAttribute('title', /forman un ciclo/)
    await expect(node).toHaveClass(/graph-has-errors/)
  }
  await expectAccessible(page, 'grafo con problemas')
  await graph.screenshot({ path: `${process.env.E2E_OUT ?? 'build'}/w8a-problemas.png` })
})

test('editar un borrador desde el grafo: añadir una fase, unirla, completar su panel y guardar', async ({
  page,
}) => {
  const key = `e2e-grafo-${Date.now().toString(36)}`
  await page.goto('/workflows/new')
  await login(page)
  await page.getByLabel('Archivo YAML del workflow').setInputFiles({
    name: `${key}.yaml`,
    mimeType: 'text/yaml',
    buffer: Buffer.from(yaml(key)),
  })
  await expect(problemsCard(page).getByText('Sin problemas')).toBeVisible()
  await page.getByRole('button', { name: 'Crear borrador' }).click()
  await page.waitForURL(`**/workflows/${key}`)
  await page.getByRole('tab', { name: 'Grafo' }).click()
  const graph = page.getByRole('group', { name: 'Grafo de Revisión E2E' })
  const box = (id: string) => graph.locator(`.react-flow__node[data-id="${id}"]`)

  await test.step('añadir una fase command detrás de fix y completar su panel', async () => {
    await box('fix').getByRole('button').click()
    await expect(page.getByRole('heading', { name: 'Fase fix' })).toBeVisible()
    await page.getByLabel('Añadir fase').selectOption('command')
    const panel = page.getByRole('region', { name: 'Fase command' })
    await expect(panel).toBeVisible()
    await expect(box('command')).toContainText('1 error')
    await panel.getByLabel('Comando').fill('./gradlew test')
    await panel.getByLabel('Comando').press('Enter')
    await expect(box('command')).toContainText('./gradlew test')
    await expect(box('command').locator('.graph-problems')).toHaveCount(0)
  })

  await test.step('unir review con command arrastrando y elegir qué worktree continúa', async () => {
    await box('review')
      .locator('.react-flow__handle.source')
      .dragTo(box('command').locator('.react-flow__handle.target'))
    await expect(graph.locator('.graph-edge')).toHaveCount(3)
    // Depende de dos fases con worktree: hay que decir cuál continúa.
    await expect(box('command')).toContainText('1 error')
    const panel = page.getByRole('region', { name: 'Fase command' })
    await expect(
      panel.getByRole('button', { name: 'Quitar la dependencia de review' }),
    ).toBeVisible()
    await panel.getByLabel('Continúa el worktree de').selectOption('fix')
    await expect(box('command').locator('.graph-problems')).toHaveCount(0)
  })

  await test.step('deshacer y rehacer el último cambio', async () => {
    const panel = page.getByRole('region', { name: 'Fase command' })
    await page.getByRole('button', { name: 'Deshacer' }).click()
    await expect(panel.getByLabel('Continúa el worktree de')).toHaveValue('')
    await page.getByRole('button', { name: 'Rehacer' }).click()
    await expect(panel.getByLabel('Continúa el worktree de')).toHaveValue('fix')
  })

  await test.step('una flecha se elige y se hace opcional', async () => {
    // La flecha de review a command: se elige con un clic al principio de su trazo (más allá
    // pasa por detrás de fix).
    const path = graph.locator('.react-flow__edge[data-id="review->command"] path').first()
    const middle = await path.evaluate((element) => {
      const p = element as unknown as {
        getTotalLength: () => number
        getPointAtLength: (at: number) => { x: number; y: number }
        getScreenCTM: () => { a: number; b: number; c: number; d: number; e: number; f: number }
      }
      const point = p.getPointAtLength(p.getTotalLength() * 0.1)
      const m = p.getScreenCTM()!
      return { x: point.x * m.a + point.y * m.c + m.e, y: point.x * m.b + point.y * m.d + m.f }
    })
    await page.mouse.click(middle.x, middle.y)
    const edge = page.getByRole('region', { name: /depende de/ })
    await expect(edge).toBeVisible()
    await edge.getByRole('checkbox').check()
    await expect(graph.locator('.graph-edge-optional')).toHaveCount(1)
  })

  await expectAccessible(page, 'grafo editable')
  await graph.screenshot({ path: `${process.env.E2E_OUT ?? 'build'}/w8b-editor.png` })
  await page.screenshot({
    path: `${process.env.E2E_OUT ?? 'build'}/w8b-pagina.png`,
    fullPage: true,
  })

  await test.step('guardar: el YAML queda con la fase nueva', async () => {
    await page.getByRole('button', { name: 'Guardar' }).click()
    await expect(page.getByText(/Guardado el/)).toBeVisible()
    await page.reload()
    const summary = page.getByRole('region', { name: 'Qué define' })
    const stages = summary.getByRole('list').first().getByRole('listitem')
    await expect(stages).toHaveCount(3)
    await expect(stages.nth(2)).toContainText('Ejecuta ./gradlew test')
    await expect(stages.nth(2)).toContainText('Depende de fix, review (opcional)')
    await expect(stages.nth(2)).toContainText('continúa el de fix')
  })
})
