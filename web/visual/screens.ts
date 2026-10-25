import { expect, type Page } from '@playwright/test'
import type { Fixture } from './paths.ts'
import type { ThemeId, ViewportId } from './inventory.ts'

/** Lo que recibe cada preparador: los ids sembrados y la combinación que se captura. */
export interface ScreenContext {
  fx: Fixture
  viewport: ViewportId
  theme: ThemeId
}

/** Lleva la página al estado de la pantalla y espera a que esté lista para la captura. */
type Preparer = (page: Page, ctx: ScreenContext) => Promise<void>

/** Espera a que la página termine de cargar: sin esqueletos y, si se pide, con ese texto. */
async function ready(page: Page, text?: string | RegExp) {
  await expect(page.locator('#main')).toBeVisible()
  if (text) await expect(page.locator('#main').getByText(text).first()).toBeVisible()
  await expect(page.locator('.skeleton')).toHaveCount(0)
}

async function goto(page: Page, path: string, text?: string | RegExp) {
  await page.goto(path)
  await ready(page, text)
}

/** El detalle de una ejecución: en el móvil, las pestañas del inspector van en su panel. */
async function run(
  page: Page,
  ctx: ScreenContext,
  runId: string,
  params: Record<string, string> = {},
) {
  const query = new URLSearchParams(params)
  if (params.tab && ctx.viewport === 'mobile') query.set('pane', 'inspector')
  await page.goto(`/runs/${runId}${query.size ? `?${query}` : ''}`)
  await expect(page.locator('header.run-header')).toBeVisible()
  await ready(page)
}

/** Fuera de la página: que no quede ningún hover ni tooltip. */
async function mouseAway(page: Page) {
  await page.mouse.move(0, 0)
}

export const PREPARERS: Record<string, Preparer> = {
  login: async (page) => {
    await page.context().clearCookies()
    await page.goto('/')
    await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
  },
  dashboard: (page) => goto(page, '/', /Necesita tu atención/),
  usage: (page) => goto(page, '/usage'),
  activity: (page) => goto(page, '/activity'),
  runners: (page) => goto(page, '/runners', 'runner-demo'),
  projects: (page) => goto(page, '/projects', 'Analizador de texto'),
  'project-work': (page, { fx }) => goto(page, `/projects/${fx.projectId}`, 'Contar palabras'),
  'project-repos': (page, { fx }) =>
    goto(page, `/projects/${fx.projectId}?tab=repos`, 'analizador-texto'),
  'project-memory': (page, { fx }) =>
    goto(page, `/projects/${fx.projectId}?tab=memory`, /Normalizar el texto/),
  'project-empty': (page, { fx }) => goto(page, `/projects/${fx.emptyProjectId}`, 'Prototipos'),
  'new-work-item': async (page, { fx }) => {
    await goto(page, `/projects/${fx.projectId}`, 'Contar palabras')
    await page.getByRole('button', { name: 'Nuevo trabajo' }).click()
    await expect(page.getByRole('dialog', { name: 'Nuevo trabajo' })).toBeVisible()
  },
  'work-item': (page, { fx }) => goto(page, `/work-items/${fx.workItemId}`, 'La suma de totales'),
  workflows: (page) => goto(page, '/workflows', 'Documentar el repositorio'),
  'workflow-summary': (page, { fx }) =>
    goto(page, `/workflows/${fx.workflowKey}?version=${fx.publishedVersionId}&view=graph`),
  'workflow-yaml': (page, { fx }) =>
    goto(page, `/workflows/${fx.workflowKey}?version=${fx.publishedVersionId}`),
  'workflow-editor': (page, { fx }) =>
    goto(page, `/workflows/${fx.workflowKey}?version=${fx.draftVersionId}&view=graph`),
  'workflow-new': (page) => goto(page, '/workflows/new'),
  'launch-workflow': async (page, { fx }) => {
    await goto(page, `/work-items/${fx.workItemId}`, 'La suma de totales')
    await page.getByRole('button', { name: 'Lanzar workflow' }).click()
    const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
    await sheet.getByLabel('Workflow').selectOption(fx.workflowKey)
    await expect(sheet.getByRole('region', { name: 'Política de cada fase' })).toBeVisible()
  },
  runs: (page) => goto(page, '/runs', 'La suma de totales'),
  'run-completed': (page, ctx) => run(page, ctx, ctx.fx.runs.completed),
  'run-waterfall': (page, ctx) => run(page, ctx, ctx.fx.runs.completed, { view: 'waterfall' }),
  'run-events': (page, ctx) => run(page, ctx, ctx.fx.runs.completed, { view: 'events' }),
  'run-prompt': (page, ctx) =>
    run(page, ctx, ctx.fx.runs.completed, { agent: ctx.fx.agents.completed, tab: 'prompt' }),
  'run-tools': (page, ctx) =>
    run(page, ctx, ctx.fx.runs.completed, { agent: ctx.fx.agents.completed, tab: 'tools' }),
  'run-verification': (page, ctx) =>
    run(page, ctx, ctx.fx.runs.completed, {
      agent: ctx.fx.agents.completed,
      tab: 'verification',
    }),
  'run-cost': (page, ctx) =>
    run(page, ctx, ctx.fx.runs.completed, { agent: ctx.fx.agents.completed, tab: 'cost' }),
  'run-artifacts': async (page, ctx) => {
    await run(page, ctx, ctx.fx.runs.documented, { agent: ctx.fx.agents.report, tab: 'artifacts' })
    await expect(page.getByRole('list', { name: 'Archivos generados' })).toContainText(
      'docs/informe.md',
    )
  },
  'run-artifact-open': async (page, ctx) => {
    await run(page, ctx, ctx.fx.runs.documented, { agent: ctx.fx.agents.report, tab: 'artifacts' })
    const list = page.getByRole('list', { name: 'Archivos generados' })
    // Con el teclado: en el móvil, la barra de navegación inferior tapa el botón.
    await list.getByRole('button', { name: 'Abrir docs/informe.md' }).press('Enter')
    const doc = list.getByRole('article', { name: 'Contenido de docs/informe.md' })
    await expect(doc.getByRole('heading', { name: 'Informe del repositorio' })).toBeVisible()
    await doc.scrollIntoViewIfNeeded()
    await page.evaluate(`document.activeElement?.blur()`)
    await mouseAway(page)
  },
  'run-memory': async (page, ctx) => {
    await run(page, ctx, ctx.fx.runs.documented, { agent: ctx.fx.agents.memory, tab: 'memory' })
    await expect(page.getByRole('region', { name: 'Lo que guardó' })).toBeVisible()
  },
  'run-active': (page, ctx) => run(page, ctx, ctx.fx.runs.active),
  'run-failed': (page, ctx) => run(page, ctx, ctx.fx.runs.failed),
  approvals: (page) => goto(page, '/approvals', 'Separar el lector'),
  'approval-pending': (page, { fx }) => goto(page, `/approvals/${fx.approvalId}`),
  'settings-colors': (page) => goto(page, '/settings'),
  'settings-icons': (page) => goto(page, '/settings?tab=icons'),
  'settings-companion': (page) => goto(page, '/settings?tab=companion'),
  'command-palette': async (page) => {
    await goto(page, '/', /Necesita tu atención/)
    await page.getByRole('button', { name: 'Buscar o ejecutar una orden' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
  },
  'theme-menu': async (page) => {
    await goto(page, '/', /Necesita tu atención/)
    await page.getByRole('button', { name: /^Tema:/ }).click()
    await expect(page.getByRole('menu')).toBeVisible()
    await mouseAway(page)
  },
  'companion-menu': async (page) => {
    await goto(page, '/', /Necesita tu atención/)
    await page.locator('.companion-btn').click()
    await expect(page.getByRole('menu')).toBeVisible()
    await mouseAway(page)
  },
  'not-found': (page) => goto(page, '/no-existe'),
}
