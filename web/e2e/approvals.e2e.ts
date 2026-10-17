import { expect, test, type Page } from '@playwright/test'
import { TEMPLATES } from '../src/components/workflow/templates.ts'
import { expectAccessible, login, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Human-in-the-loop (W5), criterio de aceptación del hito: con la plantilla «Especificar,
 * planificar e implementar», la ejecución se para en `aprobar-plan`. Pedir cambios hace que `plan`
 * reciba el comentario en su sesión y la puerta pregunte otra vez (ronda 2); aprobar arranca
 * `implement` y la ejecución termina «Completada». En otra ejecución, rechazar la hace fallar.
 *
 * Como en spec.e2e.ts, una fase `command` previa deja en el worktree la propuesta y, en
 * `.revision.json`, la salida estructurada que devuelve fake-claude.
 */

test.use({ baseURL: 'http://localhost:4173' })

const template = TEMPLATES.find((t) => t.id === 'especificar-planificar-implementar')!

const OUTPUT = { summary: 'Que add() sume', risks: ['ninguno'] }

function yaml(key: string) {
  const prepare = [
    '  - id: preparar',
    '    type: command',
    `    command: mkdir -p openspec && echo '# Propuesta' > openspec/proposal.md && printf '${JSON.stringify(OUTPUT)}' > .revision.json && git add -A && git -c user.email=e2e@skynet -c user.name=e2e commit -qm preparar`,
    '  - id: spec',
    '    type: agent',
    '    dependsOn: [preparar]',
  ].join('\n')
  return template.yaml
    .replace('id: especificar-planificar-implementar', `id: ${key}`)
    .replace('  - id: spec\n    type: agent', prepare)
    .replace('checks: [tests, diff]', 'checks: [tests]')
}

interface Approval {
  id: string
  round: number
  status: string
  workflowRunId: string
  action: string
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

/** Espera a que la puerta de la ejecución pregunte en la ronda `round`. */
async function pending(page: Page, runId: string, round: number): Promise<Approval> {
  let found: Approval | undefined
  await expect
    .poll(
      async () => {
        const list: Approval[] = await (
          await page.request.get('/api/approvals?status=PENDING')
        ).json()
        found = list.find((a) => a.workflowRunId === runId && a.round === round)
        return !!found
      },
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe(true)
  return found!
}

async function finished(page: Page, runId: string, status: string) {
  await expect
    .poll(
      async () => (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()).status,
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe(status)
}

test('una puerta se aprueba, pide cambios y se rechaza desde el centro de aprobaciones', async ({
  page,
}) => {
  test.setTimeout(600_000)
  const key = `con-puerta-${Date.now().toString(36)}`
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  await publishWorkflowViaApi(page, yaml(key))
  const { workItemId } = await workItemViaApi(page, { validationCommand: 'sh check.sh' })

  const runId = await launch(page, workItemId, key)
  const first = await pending(page, runId, 1)
  expect(first.action).toMatch(/^Aprobar el plan de .+ \(openspec\/tasks\.md\): Que add\(\) sume$/)

  await test.step('la puerta espera en el progreso, el dashboard y el menú', async () => {
    await page.goto(`/runs/${runId}`)
    const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
    const gate = progress.getByRole('button', { name: /^4\. aprobar-plan: Esperando aprobación/ })
    await expect(gate).toBeVisible()
    await gate.click()
    const detail = progress.locator('.step-detail')
    await expect(detail).toContainText(first.action)
    await expect(detail).toContainText('Riesgo medio')
    await expect(detail.getByRole('link', { name: 'Revisar' })).toHaveAttribute(
      'href',
      `/approvals/${first.id}`,
    )
    await expectAccessible(page, 'puerta en el progreso')

    await page.goto('/')
    const attention = page.getByRole('region', { name: /Necesita tu atención/ })
    await expect(
      attention.getByRole('link', { name: new RegExp(first.action.replace(/[()]/g, '\\$&')) }),
    ).toBeVisible()
    await expect(
      page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link', {
        name: /^Aprobaciones, \d+ pendientes?$/,
      }),
    ).toBeVisible()
  })

  await test.step('el centro lista la petición y su página enseña la evidencia', async () => {
    await page.goto('/approvals?status=PENDING')
    const table = page.getByRole('table', { name: 'Aprobaciones' })
    await expect(table.getByRole('link', { name: first.action })).toBeVisible()
    await expectAccessible(page, 'centro de aprobaciones')

    await table.getByRole('link', { name: first.action }).click()
    await expect(page.getByRole('heading', { level: 1, name: first.action })).toBeVisible()
    const evidence = page.getByRole('region', { name: 'Lo que dejaron sus dependencias' })
    await expect(evidence).toContainText('plan')
    await expect(page.getByRole('region', { name: 'Qué pasa con cada decisión' })).toContainText(
      'Pueden arrancar: implement.',
    )
    await expectAccessible(page, 'página de una aprobación')
  })

  await test.step('pedir cambios: plan recibe el comentario y la puerta pregunta otra vez', async () => {
    await page.getByRole('button', { name: 'Pedir cambios' }).click()
    await expect(page.getByRole('alert')).toContainText('escribe qué cambiar')
    await page.getByLabel(/Comentario/).fill('Añade también los casos negativos')
    await page.getByRole('button', { name: 'Pedir cambios' }).click()
    await expect(page.getByRole('heading', { name: /Corrigiendo|Corregido/ })).toBeVisible()

    const second = await pending(page, runId, 2)
    const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
    const plan = run.stages.find((s: { stageKey: string }) => s.stageKey === 'plan')
    expect(plan.status).toBe('SUCCEEDED')
    expect(plan.agents).toHaveLength(2)
    const correction = await (await page.request.get(`/api/agent-runs/${plan.agents[1].id}`)).json()
    const prompt = correction.prompts.map((p: { content: string }) => p.content).join('\n')
    expect(prompt).toContain('Añade también los casos negativos')

    await page.goto(`/approvals/${second.id}`)
    await expect(page.getByRole('region', { name: 'Cambios pedidos en la ronda 1' })).toContainText(
      'Añade también los casos negativos',
    )
    await expectAccessible(page, 'segunda ronda de una aprobación')

    await page.getByRole('button', { name: 'Aprobar' }).click()
    await expect(page.getByText('Aprobada', { exact: true })).toBeVisible()
    await finished(page, runId, 'SUCCEEDED')
  })

  await test.step('rechazar hace fallar la ejecución', async () => {
    const other = await launch(page, workItemId, key)
    const approval = await pending(page, other, 1)
    await page.goto(`/approvals/${approval.id}`)
    await page.getByLabel(/Comentario/).fill('No es lo que pedí')
    await page.getByRole('button', { name: 'Rechazar' }).click()
    await expect(page.getByText('Rechazada', { exact: true })).toBeVisible()
    await finished(page, other, 'FAILED')
    const run = await (await page.request.get(`/api/workflow-runs/${other}`)).json()
    expect(
      run.stages.map((s: { stageKey: string; status: string }) => [s.stageKey, s.status]),
    ).toEqual([
      ['preparar', 'SUCCEEDED'],
      ['spec', 'SUCCEEDED'],
      ['plan', 'SUCCEEDED'],
      ['aprobar-plan', 'FAILED'],
      ['implement', 'CANCELLED'],
    ])
  })
})
