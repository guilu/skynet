import { expect, test, type Page } from '@playwright/test'
import { TEMPLATES } from '../src/components/workflow/templates.ts'
import { expectAccessible, login, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Proveedores (PV-D, con Antigravity desde AR-B), criterio de aceptación del hito: la plantilla
 * «Revisión con Codex y Antigravity» (calidad con Codex, seguridad con Antigravity y el resto con
 * Claude Code) termina «Completada» con
 * hallazgos (corrige) y sin ellos (omite la corrección). Y un agente suelto se lanza con otro
 * proveedor y modelo desde «Lanzar workflow».
 *
 * El runner de las E2E tiene fake-claude, fake-codex y fake-agy, y los tres revisores devuelven
 * lo que la fase `tests` dejó en `.revision.json`.
 */

test.use({ baseURL: 'http://localhost:4173' })

const template = TEMPLATES.find((t) => t.id === 'revision-con-codex-y-antigravity')!

/** La plantilla con otra clave y comandos que valen en el repositorio de las E2E. */
function yaml(key: string, blocking: boolean) {
  const output = JSON.stringify({ hasBlockingFindings: blocking, summary: 'revisado' })
  return template.yaml
    .replace('id: revision-con-codex-y-antigravity', `id: ${key}`)
    .replace(/command: \.\/gradlew test .*\n/, `command: printf '${output}' > .revision.json\n`)
    .replace(
      /command: \.\/gradlew test .*\n/,
      'command: test -f .revision.json && git status --short\n',
    )
}

interface RunStage {
  stageKey: string
  status: string
  agents: {
    id: string
    childKey: string | null
    provider: string
    structuredOutput: { hasBlockingFindings?: boolean } | null
  }[]
}

async function launch(page: Page, workItemId: string, key: string): Promise<string> {
  await page.goto(`/work-items/${workItemId}`)
  await page.getByRole('button', { name: 'Lanzar workflow' }).click()
  const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
  await sheet.getByLabel('Workflow').selectOption(key)
  const policies = sheet.getByRole('region', { name: 'Política de cada fase' })
  // Lo que Codex y Antigravity no admiten sale tachado.
  await expect(policies.getByRole('listitem').filter({ hasText: 'review.calidad' })).toContainText(
    'Codex',
  )
  await expect(
    policies.getByRole('listitem').filter({ hasText: 'review.seguridad' }),
  ).toContainText('no lo admite Antigravity CLI')
  // Todas las fases fijan su agente: no hay selector de proveedor.
  await expect(sheet.getByLabel('Proveedor')).toHaveCount(0)
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

const statuses = (stages: RunStage[]) => stages.map((s) => [s.stageKey, s.status])

// @lento: en una PR solo corre con la etiqueta e2e-completo (ver scripts/ci/e2e-alcance.sh).
test(
  'la revisión con Codex y Antigravity corrige solo cuando hay hallazgos',
  { tag: '@lento' },
  async ({ page }) => {
    test.setTimeout(480_000)
    const suffix = Date.now().toString(36)
    const withFindings = `multi-con-hallazgos-${suffix}`
    const clean = `multi-sin-hallazgos-${suffix}`
    await page.goto('/')
    await login(page)
    await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

    await test.step('el runner tiene los tres proveedores', async () => {
      const providers = await (await page.request.get('/api/providers')).json()
      expect(
        providers.map((p: { id: string; onlineRunners: number }) => [p.id, p.onlineRunners > 0]),
      ).toEqual([
        ['claude-code', true],
        ['codex', true],
        ['antigravity', true],
      ])
    })

    await test.step('elegir la plantilla en Nuevo workflow', async () => {
      await page.goto('/workflows/new')
      await page.getByLabel('Plantilla').selectOption({ label: 'Revisión con Codex y Antigravity' })
      await expect(
        page.locator('.monaco-editor').getByText('id: revision-con-codex-y-antigravity'),
      ).toBeVisible()
      const summary = page.getByRole('region', { name: 'Qué define' })
      await expect(
        summary.getByRole('listitem').filter({ hasText: 'calidad' }).last(),
      ).toContainText('Codex')
      await expect(
        summary.getByRole('listitem').filter({ hasText: 'seguridad' }).last(),
      ).toContainText('Antigravity CLI')
      await expectAccessible(page, 'plantilla «Revisión con Codex y Antigravity»')
    })

    await publishWorkflowViaApi(page, yaml(withFindings, true))
    await publishWorkflowViaApi(page, yaml(clean, false))
    const { workItemId } = await workItemViaApi(page)

    await test.step('con hallazgos, corrige y pasa los tests finales', async () => {
      const runId = await launch(page, workItemId, withFindings)
      const stages = await finished(page, runId)
      expect(statuses(stages)).toEqual([
        ['implement', 'SUCCEEDED'],
        ['tests', 'SUCCEEDED'],
        ['review', 'SUCCEEDED'],
        ['fix', 'SUCCEEDED'],
        ['tests-finales', 'SUCCEEDED'],
      ])
      const review = stages.find((s) => s.stageKey === 'review')!
      expect(
        review.agents.map((a) => [a.childKey, a.provider, a.structuredOutput?.hasBlockingFindings]),
      ).toEqual(
        expect.arrayContaining([
          ['calidad', 'codex', true],
          ['seguridad', 'antigravity', true],
        ]),
      )

      // El agente de Antigravity: su proveedor en la cabecera y bifurcar desactivado con el motivo.
      const agy = review.agents.find((a) => a.provider === 'antigravity')!
      await page.goto(`/runs/${runId}?agent=${agy.id}`)
      const header = page.locator('header.run-header')
      await expect(header.getByTitle('Proveedor')).toHaveText(/Antigravity CLI/)
      await expect(header.getByRole('button', { name: 'Bifurcar…' })).toBeDisabled()
      await expect(
        header.getByText('Antigravity CLI no permite bifurcar una sesión.'),
      ).toBeVisible()
      await expectAccessible(page, 'agente de Antigravity')

      // El de Codex sí bifurca.
      const codex = review.agents.find((a) => a.provider === 'codex')!
      await page.goto(`/runs/${runId}?agent=${codex.id}`)
      await expect(header.getByTitle('Proveedor')).toHaveText(/Codex/)
      await expect(header.getByRole('button', { name: 'Bifurcar…' })).toBeEnabled()
    })

    await test.step('sin hallazgos, omite la corrección y pasa los tests finales', async () => {
      const stages = await finished(page, await launch(page, workItemId, clean))
      expect(statuses(stages)).toEqual([
        ['implement', 'SUCCEEDED'],
        ['tests', 'SUCCEEDED'],
        ['review', 'SUCCEEDED'],
        ['fix', 'SKIPPED'],
        ['tests-finales', 'SUCCEEDED'],
      ])
    })
  },
)

test('un agente suelto con Antigravity y su modelo, lanzado desde la web', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const { workItemId } = await workItemViaApi(page)

  await page.goto(`/work-items/${workItemId}`)
  await page.getByRole('button', { name: 'Lanzar workflow' }).click()
  const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
  await sheet.getByLabel('Prompt').fill('Revisa calc.py')
  await sheet.getByLabel('Proveedor').selectOption({ label: 'Antigravity CLI' })
  await expect(sheet.getByLabel('Modelo')).toHaveAttribute(
    'placeholder',
    'el predeterminado de Antigravity CLI',
  )
  await sheet.getByLabel('Modelo').fill('gemini-3-flash')
  await expectAccessible(page, 'lanzar con otro proveedor')
  await sheet.getByRole('button', { name: 'Lanzar', exact: true }).click()
  await page.waitForURL('**/runs/**')

  const header = page.locator('header.run-header')
  await expect(header.locator('.hero-top').getByText('Completada')).toBeVisible({
    timeout: 90_000,
  })
  await expect(header.getByTitle('Proveedor')).toHaveText(/Antigravity CLI/)
  await expect(header.getByTitle('Modelo')).toHaveText(/gemini-3-flash/)
  // Antigravity continúa la conversación: la caja de mensajes está activa.
  await page.getByRole('tab', { name: 'Conversación' }).click()
  await expect(
    page.getByRole('form', { name: 'Continuar la conversación' }).getByLabel('Mensaje'),
  ).toBeEnabled()
})
