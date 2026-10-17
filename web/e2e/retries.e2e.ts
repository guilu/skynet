import { expect, test, type Page } from '@playwright/test'
import { login, postApi, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Reintentos automáticos (W6-A): una fase `command` con `retry` falla la primera vez después de
 * ensuciar el worktree (un fichero nuevo, un commit y un cambio sin confirmar). El intento 2 parte
 * del worktree tal como lo dejó `implement`, termina bien y la ejecución queda «Completada». Con
 * `on: [lost]`, el mismo fallo no se reintenta y la ejecución falla.
 */

test.use({ baseURL: 'http://localhost:4173' })

const out = process.env.E2E_OUT ?? '/tmp'

function yaml(key: string, marker: string, on: string) {
  const dirty = [
    `if [ ! -f ${marker} ]; then touch ${marker}`,
    'echo basura > basura.txt && git add -A && git -c user.email=e2e@skynet -c user.name=e2e commit -qm basura',
    'echo sucio >> calc.py; exit 1; fi',
    "test ! -f basura.txt && ! git log --format=%s | grep -qx basura && ! grep -q sucio calc.py && grep -q 'a + b' calc.py",
  ].join('; ')
  return [
    `id: ${key}`,
    'stages:',
    '  - id: implement',
    '    type: agent',
    '    prompt: Arregla add()',
    '  - id: ensucia',
    '    type: command',
    '    dependsOn: [implement]',
    `    command: ${JSON.stringify(dirty)}`,
    `    retry: {maxAttempts: 2, delay: 1s${on}}`,
    '',
  ].join('\n')
}

interface RunStage {
  stageKey: string
  attempt: number
  status: string
  agents: { kind: string; failure: string | null }[]
}

async function launched(page: Page, on: string): Promise<string> {
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const suffix = Date.now().toString(36)
  const key = `reintenta-${suffix}`
  const definitionId = await publishWorkflowViaApi(page, yaml(key, `${out}/w6a-${suffix}`, on))
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const run = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    definitionId,
  })
  return run.id
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
      { timeout: 120_000, intervals: [1_000] },
    )
    .toBe(status)
  return stages
}

const attempts = (stages: RunStage[]) =>
  stages.map((s) => [s.stageKey, s.attempt, s.status, s.agents.map((a) => a.failure)])

test('un fallo pasajero se reintenta desde el worktree de su dependencia', async ({ page }) => {
  test.setTimeout(240_000)
  const runId = await launched(page, '')

  const stages = await finished(page, runId, 'SUCCEEDED')
  expect(attempts(stages)).toEqual([
    ['implement', 1, 'SUCCEEDED', [null]],
    ['ensucia', 1, 'FAILED', ['error']],
    ['ensucia', 2, 'SUCCEEDED', [null]],
  ])
  expect(stages[2].agents[0].kind).toBe('RETRY')

  await page.goto(`/runs/${runId}`)
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  // Cada fase una vez, con su último intento.
  await expect(progress.getByRole('button', { name: /^\d\. / })).toHaveCount(2)
  await progress.getByRole('button', { name: /^2\. ensucia:/ }).click()
  const detail = progress.locator('.step-detail')
  await expect(detail).toContainText('intento 2 de 2')
  await expect(detail).toContainText('Intentos anteriores')
  await expect(detail).toContainText('terminó con error')
})

test('un fallo que no está en `on` no se reintenta', async ({ page }) => {
  test.setTimeout(240_000)
  const runId = await launched(page, ', on: [lost]')

  const stages = await finished(page, runId, 'FAILED')
  expect(attempts(stages)).toEqual([
    ['implement', 1, 'SUCCEEDED', [null]],
    ['ensucia', 1, 'FAILED', ['error']],
  ])
})
