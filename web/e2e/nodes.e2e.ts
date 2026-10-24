import { expect, test, type Page } from '@playwright/test'
import { TEMPLATES } from '../src/components/workflow/templates.ts'
import { expectAccessible, login, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Nodos (W3), criterio de aceptación: la plantilla «Revisión en paralelo» (comando, fase
 * `parallel` con salida estructurada, corrección con `condition` y tests finales con `fix?`),
 * publicada y lanzada desde la web, termina «Completada» con hallazgos (corrige) y sin ellos (omite
 * la corrección).
 *
 * Con W7-C, además, el grafo de la ejecución muestra los carriles de la plantilla y pasa de
 * pendiente a completada sin recargar.
 *
 * El runner de las E2E lanza fake-claude con `FAKE_CLAUDE_STRUCTURED_OUTPUT=@.revision.json`: cada
 * revisor devuelve lo que la fase `tests` dejó en ese fichero del worktree, que los hijos de la fase
 * `parallel` ven en su copia.
 */

test.use({ baseURL: 'http://localhost:4173' })

const template = TEMPLATES.find((t) => t.id === 'revision-en-paralelo')!

/** La plantilla con otra clave y comandos que valen en el repositorio de las E2E. */
function yaml(key: string, blocking: boolean) {
  return template.yaml
    .replace('id: revision-en-paralelo', `id: ${key}`)
    .replace(
      /command: \.\/gradlew test .*\n/,
      `command: printf '{"hasBlockingFindings":${blocking}}' > .revision.json\n`,
    )
    .replace(
      /command: \.\/gradlew test .*\n/,
      'command: test -f .revision.json && git status --short\n',
    )
}

interface RunStage {
  stageKey: string
  status: string
  agents: { childKey: string | null; structuredOutput: { hasBlockingFindings?: boolean } | null }[]
}

async function launch(page: Page, workItemId: string, key: string): Promise<string> {
  await page.goto(`/work-items/${workItemId}`)
  await page.getByRole('button', { name: 'Lanzar workflow' }).click()
  const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
  await sheet.getByLabel('Workflow').selectOption(key)
  const policies = sheet.getByRole('region', { name: 'Política de cada fase' })
  await expect(policies.getByText('review.calidad')).toBeVisible()
  await expect(policies.getByText('review.seguridad')).toBeVisible()
  await sheet.getByRole('button', { name: 'Lanzar', exact: true }).click()
  await page.waitForURL('**/runs/**')
  return page.url().split('/runs/')[1].split('?')[0]
}

async function finished(page: Page, runId: string): Promise<RunStage[]> {
  let stages: RunStage[] = []
  await expect
    .poll(
      async () => {
        const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
        stages = run.stages
        return run.status
      },
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe('SUCCEEDED')
  return stages
}

/** Abre una fase en el progreso de la ejecución y devuelve su detalle. */
async function openStage(page: Page, position: number, key: string) {
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  await progress.getByRole('button', { name: new RegExp(`^${position}\\. ${key}:`) }).click()
  return progress.locator('.step-detail')
}

const statuses = (stages: RunStage[]) => stages.map((s) => [s.stageKey, s.status])

/** El grafo de la ejecución y sus cinco fases (sin los hijos de `review`). */
function runGraph(page: Page) {
  const graph = page
    .getByRole('navigation', { name: 'Fases y agentes' })
    .getByRole('group', { name: 'Fases de la ejecución' })
  return { graph, stages: graph.locator('.graph-node:not(.graph-child)') }
}

// @lento: en una PR solo corre con la etiqueta e2e-completo (ver scripts/ci/e2e-alcance.sh).
test(
  'la plantilla «Revisión en paralelo» corrige solo cuando hay hallazgos',
  { tag: '@lento' },
  async ({ page }) => {
    test.setTimeout(480_000)
    const suffix = Date.now().toString(36)
    const withFindings = `con-hallazgos-${suffix}`
    const clean = `sin-hallazgos-${suffix}`
    await page.goto('/')
    await login(page)
    await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

    await test.step('elegir la plantilla en Nuevo workflow', async () => {
      await page.goto('/workflows/new')
      await page.getByLabel('Plantilla').selectOption({ label: 'Revisión en paralelo' })
      await expect(
        page.locator('.monaco-editor').getByText('id: revision-en-paralelo'),
      ).toBeVisible()
      const stages = page
        .getByRole('region', { name: 'Qué define' })
        .getByRole('list')
        .first()
        .getByRole('listitem')
      await expect(stages).toHaveCount(5)
      await expect(stages.nth(2)).toContainText('A la vez: calidad, seguridad')
      await expect(stages.nth(3)).toContainText('Solo si review.hasBlockingFindings')
      await expectAccessible(page, 'plantilla «Revisión en paralelo»')
    })

    await test.step('importarla con los comandos del repositorio y publicarla', async () => {
      await page.getByLabel('Archivo YAML del workflow').setInputFiles({
        name: `${withFindings}.yaml`,
        mimeType: 'text/yaml',
        buffer: Buffer.from(yaml(withFindings, true)),
      })
      await expect(page.locator('.monaco-editor').getByText(`id: ${withFindings}`)).toBeVisible()
      await page.getByRole('button', { name: 'Crear borrador' }).click()
      await page.waitForURL(`**/workflows/${withFindings}`)
      await page.getByRole('button', { name: 'Publicar…' }).click()
      const sheet = page.getByRole('dialog', { name: `Publicar ${withFindings} v1` })
      const commands = sheet.getByRole('region', { name: /Ejecuta estos comandos/ })
      await expect(commands.getByRole('listitem')).toHaveCount(2)
      await expect(commands.getByText('.revision.json').first()).toBeVisible()
      await expectAccessible(page, 'publicar un workflow con comandos')
      await sheet.getByRole('button', { name: 'Publicar v1' }).click()
      await expect(sheet).toBeHidden()
    })
    await publishWorkflowViaApi(page, yaml(clean, false))
    const { workItemId } = await workItemViaApi(page)

    await test.step('con hallazgos, corrige y pasa los tests finales', async () => {
      const runId = await launch(page, workItemId, withFindings)
      // W7-C: el grafo con un carril por macrofase de la plantilla; recién lanzada, aún no ha
      // terminado y se pone al día sola, sin recargar.
      const { graph, stages: boxes } = runGraph(page)
      await expect(graph.locator('.graph-lane')).toHaveText(['Implementación', 'Tests', 'Revisión'])
      await expect(boxes).toHaveCount(5)
      await expect(boxes.and(page.locator('.graph-state-done'))).not.toHaveCount(5)
      const stages = await finished(page, runId)
      await expect(boxes.and(page.locator('.graph-state-done'))).toHaveCount(5)
      expect(statuses(stages)).toEqual([
        ['implement', 'SUCCEEDED'],
        ['tests', 'SUCCEEDED'],
        ['review', 'SUCCEEDED'],
        ['fix', 'SUCCEEDED'],
        ['tests-finales', 'SUCCEEDED'],
      ])
      const review = stages.find((s) => s.stageKey === 'review')!
      expect(
        review.agents.map((a) => [a.childKey, a.structuredOutput?.hasBlockingFindings]),
      ).toEqual(
        expect.arrayContaining([
          ['calidad', true],
          ['seguridad', true],
        ]),
      )
      const tests = await openStage(page, 2, 'tests')
      await expect(tests).toContainText(`ejecuta printf`)
      await expect(tests.getByText('Código de salida 0')).toBeVisible()
      const parallel = await openStage(page, 3, 'review')
      await expect(parallel).toContainText('2 agentes a la vez')
      await expect(parallel.getByRole('button', { name: /^calidad / })).toBeVisible()
      await expect(parallel.getByRole('button', { name: /^seguridad / })).toBeVisible()
      await expect(parallel.getByText('Salida: hasBlockingFindings: sí')).toHaveCount(2)
      const fix = await openStage(page, 4, 'fix')
      await expect(fix).toContainText('review.hasBlockingFindings se cumplió')
      await expectAccessible(page, 'ejecución con fase paralela y condición')
    })

    await test.step('sin hallazgos, omite la corrección y pasa los tests finales', async () => {
      const stages = await finished(page, await launch(page, workItemId, clean))
      const { graph, stages: boxes } = runGraph(page)
      await expect(boxes.and(page.locator('.graph-state-done'))).toHaveCount(4)
      await expect(boxes.and(page.locator('.graph-state-skip'))).toHaveCount(1)
      await expect(graph.locator('.graph-edge-dim')).toHaveCount(1)
      expect(statuses(stages)).toEqual([
        ['implement', 'SUCCEEDED'],
        ['tests', 'SUCCEEDED'],
        ['review', 'SUCCEEDED'],
        ['fix', 'SKIPPED'],
        ['tests-finales', 'SUCCEEDED'],
      ])
      expect(stages.find((s) => s.stageKey === 'fix')!.agents).toHaveLength(0)
      const fix = await openStage(page, 4, 'fix')
      await expect(fix).toContainText('Omitida: review.hasBlockingFindings es falsa')
      await expectAccessible(page, 'grafo de la ejecución con una fase omitida')
    })
  },
)
