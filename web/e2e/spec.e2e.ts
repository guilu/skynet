import { expect, test, type Page } from '@playwright/test'
import { TEMPLATES } from '../src/components/workflow/templates.ts'
import { expectAccessible, login, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Resultado estructurado (W4-C), criterio de aceptación del hito: con la plantilla «Especificar,
 * planificar e implementar», si la especificación no deja `openspec/proposal.md` la fase falla con
 * ese motivo y no arranca el plan; si lo deja, el plan recibe su resumen en el prompt y la ejecución
 * termina «Completada».
 *
 * fake-claude no escribe la propuesta (aplica siempre el mismo fixture, que corrige `calc.py`), así
 * que una fase `command` previa, `preparar`, deja en el worktree lo que escribiría el analista: la
 * propuesta, si toca, y en `.revision.json` la salida estructurada que devuelve fake-claude. Como el
 * analista ya corrige `calc.py`, la implementación no tiene nada más que cambiar: aquí solo comprueba
 * `tests` (`diff` lo prueban los tests del motor). La puerta `aprobar-plan` se quita: la prueba
 * approvals.e2e.ts.
 */

test.use({ baseURL: 'http://localhost:4173' })

const template = TEMPLATES.find((t) => t.id === 'especificar-planificar-implementar')!

const OUTPUT = { summary: 'Que add() sume', risks: ['ninguno'] }

/**
 * La plantilla con otra clave, la fase `preparar` delante, sin la puerta y solo `tests` en la
 * implementación.
 */
function yaml(key: string, withProposal: boolean) {
  const proposal = withProposal
    ? `mkdir -p openspec && echo '# Propuesta' > openspec/proposal.md && `
    : ''
  const prepare = [
    '  - id: preparar',
    '    type: command',
    `    command: ${proposal}printf '${JSON.stringify(OUTPUT)}' > .revision.json && git add -A && git -c user.email=e2e@skynet -c user.name=e2e commit -qm preparar`,
    '  - id: spec',
    '    type: agent',
    '    dependsOn: [preparar]',
  ].join('\n')
  return template.yaml
    .replace('id: especificar-planificar-implementar', `id: ${key}`)
    .replace('  - id: spec\n    type: agent', prepare)
    .replace('checks: [tests, diff]', 'checks: [tests]')
    .replace(/ {2}- id: aprobar-plan\n(?: {4}.*\n)+/, '')
    .replace('dependsOn: [aprobar-plan]', 'dependsOn: [plan]')
}

interface RunStage {
  stageKey: string
  status: string
  agents: { id: string }[]
}

async function launch(page: Page, workItemId: string, key: string): Promise<string> {
  await page.goto(`/work-items/${workItemId}`)
  await page.getByRole('button', { name: 'Lanzar workflow' }).click()
  const sheet = page.getByRole('dialog', { name: 'Lanzar workflow' })
  await sheet.getByLabel('Workflow').selectOption(key)
  await sheet.getByRole('button', { name: 'Lanzar', exact: true }).click()
  await page.waitForURL('**/runs/**')
  return page.url().split('/runs/')[1].split('?')[0]
}

async function finished(page: Page, runId: string, status: string): Promise<RunStage[]> {
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
    .toBe(status)
  return stages
}

/** Abre una fase en el progreso de la ejecución y devuelve su detalle. */
async function openStage(page: Page, position: number, key: string) {
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  await progress.getByRole('button', { name: new RegExp(`^${position}\\. ${key}:`) }).click()
  return progress.locator('.step-detail')
}

const statuses = (stages: RunStage[]) => stages.map((s) => [s.stageKey, s.status])

test('la plantilla «Especificar, planificar e implementar» exige la propuesta', async ({
  page,
}) => {
  test.setTimeout(480_000)
  const suffix = Date.now().toString(36)
  const withProposal = `con-propuesta-${suffix}`
  const withoutProposal = `sin-propuesta-${suffix}`
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  await test.step('elegir la plantilla en Nuevo workflow', async () => {
    await page.goto('/workflows/new')
    await page
      .getByLabel('Plantilla')
      .selectOption({ label: 'Especificar, planificar e implementar' })
    await expect(
      page.locator('.monaco-editor').getByText('id: especificar-planificar-implementar'),
    ).toBeVisible()
    const stages = page
      .getByRole('region', { name: 'Qué define' })
      .getByRole('list')
      .first()
      .getByRole('listitem')
    await expect(stages).toHaveCount(4)
    await expect(stages.nth(0)).toContainText('Comprueba artifacts openspec/proposal.md')
    await expect(stages.nth(2)).toContainText('Pide aprobar')
    await expect(stages.nth(3)).toContainText('Comprueba tests, diff')
    await expectAccessible(page, 'plantilla «Especificar, planificar e implementar»')
  })

  await publishWorkflowViaApi(page, yaml(withProposal, true))
  await publishWorkflowViaApi(page, yaml(withoutProposal, false))
  const { workItemId } = await workItemViaApi(page, { validationCommand: 'sh check.sh' })

  await test.step('sin propuesta, spec falla con el motivo y plan no arranca', async () => {
    const stages = await finished(page, await launch(page, workItemId, withoutProposal), 'FAILED')
    expect(statuses(stages)).toEqual([
      ['preparar', 'SUCCEEDED'],
      ['spec', 'FAILED'],
      ['plan', 'CANCELLED'],
      ['implement', 'CANCELLED'],
    ])
    expect(stages.find((s) => s.stageKey === 'plan')!.agents).toHaveLength(0)
    const spec = await openStage(page, 2, 'spec')
    await expect(spec).toContainText('No pasó artifacts: `openspec/proposal.md` no existe')
    const checks = spec.getByRole('list', { name: 'Comprobaciones de la fase' })
    await expect(checks.getByRole('listitem')).toHaveText([
      /artifacts openspec\/proposal\.md: no pasa · `openspec\/proposal\.md` no existe/,
    ])
    await expectAccessible(page, 'fase que no pasó sus comprobaciones')

    await page.getByRole('tab', { name: 'Verificación' }).click()
    const verified = page
      .getByRole('heading', { name: 'Verificado por Skynet' })
      .locator('xpath=ancestor::section[1]')
    await expect(verified.getByText('Comprobaciones de la fase')).toBeVisible()
    await expect(verified.getByText('`openspec/proposal.md` no existe')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Salida declarada' })).toBeVisible()
    await expectAccessible(page, 'verificación de una fase')
  })

  await test.step('con propuesta, plan recibe el resumen y la ejecución termina', async () => {
    const stages = await finished(page, await launch(page, workItemId, withProposal), 'SUCCEEDED')
    expect(statuses(stages)).toEqual([
      ['preparar', 'SUCCEEDED'],
      ['spec', 'SUCCEEDED'],
      ['plan', 'SUCCEEDED'],
      ['implement', 'SUCCEEDED'],
    ])
    const planAgent = stages.find((s) => s.stageKey === 'plan')!.agents[0].id
    const detail = await (await page.request.get(`/api/agent-runs/${planAgent}`)).json()
    const prompt = detail.prompts.map((p: { content: string }) => p.content).join('\n')
    expect(prompt).toContain(`Planifica`)
    expect(prompt).toContain(OUTPUT.summary)
    expect(prompt).toContain('Riesgos: ["ninguno"]')

    const plan = await openStage(page, 3, 'plan')
    await expect(plan).toContainText('Recibe spec.output.summary, spec.output.risks')
    const implement = await openStage(page, 4, 'implement')
    await expect(
      implement.getByRole('list', { name: 'Comprobaciones de la fase' }).getByRole('listitem'),
    ).toHaveText([/tests: pasa/])
  })
})
