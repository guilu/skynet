import { writeFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { login, postApi, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Continuar tras la cuota (CQ-A): fake-claude se queda sin cuota una vez (el fichero testigo
 * FAKE_CLAUDE_QUOTA, que scripts/e2e-service.sh apunta a $E2E_OUT/claude-sin-cuota). La fase
 * espera a que se renueve la ventana de 5 h, la página lo dice con su hora, «Reintentar ahora» la
 * arranca ya y el agente reanuda su sesión hasta terminar.
 */

test.use({ baseURL: 'http://localhost:4173' })

const out = process.env.E2E_OUT ?? '/tmp'

interface RunStage {
  stageKey: string
  attempt: number
  status: string
  quotaWait: { provider: string; window: string | null; resetsAt: string | null } | null
  agents: { kind: string; failure: string | null }[]
}

async function run(page: import('@playwright/test').Page, runId: string) {
  return (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()) as {
    status: string
    stages: RunStage[]
  }
}

test('una fase sin cuota espera a que se renueve y sigue con su sesión', async ({ page }) => {
  test.setTimeout(240_000)
  // La ventana se renueva en 20 s: tiempo de ver la espera sin bloquear las E2E siguientes.
  writeFileSync(`${out}/claude-sin-cuota`, '20')

  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const key = `sin-cuota-${Date.now().toString(36)}`
  const definitionId = await publishWorkflowViaApi(
    page,
    [
      `id: ${key}`,
      'stages:',
      '  - id: implement',
      '    type: agent',
      '    prompt: Arregla add()',
      '',
    ].join('\n'),
  )
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const { id: runId } = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    definitionId,
  })

  let waiting: RunStage | undefined
  await expect
    .poll(
      async () => {
        waiting = (await run(page, runId)).stages.find((s) => s.quotaWait)
        return waiting?.quotaWait?.provider ?? null
      },
      { timeout: 120_000, intervals: [1_000] },
    )
    .toBe('claude-code')
  expect(waiting!.quotaWait!.window).toBe('five_hour')

  await page.goto(`/runs/${runId}`)
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  await progress.getByRole('button', { name: /^1\. implement:/ }).click()
  const detail = progress.locator('.step-detail')
  await expect(detail).toContainText('Esperando la cuota de Claude Code')
  await expect(detail).toContainText('ventana de 5 h')
  await detail.getByRole('button', { name: 'Reintentar ahora' }).click()

  await expect
    .poll(async () => (await run(page, runId)).status, { timeout: 120_000, intervals: [1_000] })
    .toBe('SUCCEEDED')
  const stages = (await run(page, runId)).stages
  expect(
    stages.map((s) => [s.stageKey, s.attempt, s.status, s.agents.map((a) => a.failure)]),
  ).toEqual([
    ['implement', 1, 'FAILED', ['quota']],
    ['implement', 2, 'SUCCEEDED', [null]],
  ])
  expect(stages[1].agents[0].kind).toBe('RESUME')

  // Que la cuenta vuelva a tener cuota antes de la siguiente E2E.
  const resetsAt = Date.parse(waiting!.quotaWait!.resetsAt!)
  await page.waitForTimeout(Math.max(0, resetsAt - Date.now() + 1_000))
})
