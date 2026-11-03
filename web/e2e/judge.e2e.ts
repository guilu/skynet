import { writeFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { login, postApi, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * El juez con rúbrica (VA-B): fake-codex juzga y suspende una vez con un hallazgo (el testigo
 * FAKE_CODEX_JUDGE de scripts/e2e-service.sh), el agente de implement corrige en su sesión con lo
 * que encontró y la segunda ronda aprueba. La página enseña la nota, los criterios y el hallazgo.
 */

test.use({ baseURL: 'http://localhost:4173' })

const out = process.env.E2E_OUT ?? '/tmp'

interface RunStage {
  stageKey: string
  status: string
  agents: { provider: string; kind: string }[]
  judge: {
    status: string
    round: number
    score: number | null
    passed: boolean | null
    judgements: { judge: string; criteria: { id: string; score: number }[] }[]
  } | null
}

async function run(page: Page, runId: string) {
  return (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()) as {
    status: string
    stages: RunStage[]
  }
}

test('un juez que suspende manda corregir y aprueba en la segunda ronda', async ({ page }) => {
  test.setTimeout(240_000)
  writeFileSync(`${out}/juez-suspende`, 'calc.py:2 add() no trata los negativos')

  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const key = `juez-${Date.now().toString(36)}`
  const definitionId = await publishWorkflowViaApi(
    page,
    [
      `id: ${key}`,
      'agents:',
      '  juez:',
      '    provider: codex',
      'stages:',
      '  - id: implement',
      '    type: agent',
      '    prompt: Arregla add()',
      '  - id: juzgar',
      '    type: verification',
      '    dependsOn: [implement]',
      '    judge:',
      '      agents: [{agent: juez, lens: correctness}]',
      '      rubric:',
      '        - {id: resuelve, text: "Arregla add()", weight: 2, required: true}',
      '        - {id: tests, text: "Tiene un test"}',
      '    fix: implement',
      '',
    ].join('\n'),
  )
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const { id: runId } = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    definitionId,
  })

  await expect
    .poll(async () => (await run(page, runId)).status, { timeout: 180_000, intervals: [1_000] })
    .toBe('SUCCEEDED')
  const stages = (await run(page, runId)).stages
  const implement = stages.find((s) => s.stageKey === 'implement')!
  // El agente de implement corrigió en su sesión.
  expect(implement.agents.map((a) => a.kind)).toEqual(['START', 'RESUME'])
  const judge = stages.find((s) => s.stageKey === 'juzgar')!.judge!
  expect([judge.status, judge.round, judge.passed, judge.score]).toEqual(['PASSED', 2, true, 5])
  expect(judge.judgements[0].criteria.map((c) => c.id)).toEqual(['resuelve', 'tests'])

  await page.goto(`/runs/${runId}`)
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  await expect(progress).toContainText('5 / 5 · aprobado por 1 de 1')
  await progress.getByRole('button', { name: /^2\. juzgar/ }).click()
  const panel = page.getByRole('region', { name: 'Juez de la fase' })
  await expect(panel).toContainText('aprobado · aprueba 1 de 1')
  await expect(panel).toContainText('ronda 2')
  await expect(panel.getByRole('table')).toContainText('resuelve')
})
