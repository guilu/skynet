import { writeFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { login, postApi, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * La guía de pruebas como fase (VA-D): una fase `command` deja una guía en el cambio, fake-codex la
 * ejecuta y falla una vez el paso que dice el testigo FAKE_CODEX_GUIDE de scripts/e2e-service.sh,
 * el agente de implement corrige en su sesión y la segunda ronda pasa. La página enseña los pasos
 * por sección, con su estado.
 */

test.use({ baseURL: 'http://localhost:4173' })

const out = process.env.E2E_OUT ?? '/tmp'

const GUIDE = [
  '# Probar la suma',
  '',
  '## 1. Tests',
  '',
  '1. Pasan los tests de calc.py',
  '2. add() suma los negativos',
  '',
  '## 4. En GitHub',
  '',
  '1. La PR sale en verde',
  '',
].join('\\n')

interface RunStage {
  stageKey: string
  status: string
  agents: { provider: string; kind: string }[]
  judge: {
    status: string
    round: number
    passed: boolean | null
    guide: { guides: string[]; steps: { status: string }[]; note: string | null } | null
  } | null
}

async function run(page: Page, runId: string) {
  return (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()) as {
    status: string
    stages: RunStage[]
  }
}

test('una guía que falla un paso manda corregir y pasa en la segunda ronda', async ({ page }) => {
  test.setTimeout(240_000)
  writeFileSync(`${out}/guia-falla`, 'negativos')

  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const key = `guia-${Date.now().toString(36)}`
  const definitionId = await publishWorkflowViaApi(
    page,
    [
      `id: ${key}`,
      'agents:',
      '  probador:',
      '    provider: codex',
      'stages:',
      '  - id: implement',
      '    type: agent',
      '    prompt: Arregla add()',
      '  - id: escribir-guia',
      '    type: command',
      '    dependsOn: [implement]',
      `    command: "mkdir -p docs/tests && printf '${GUIDE}' > docs/tests/probar-suma.md"`,
      '  - id: guia',
      '    type: verification',
      '    dependsOn: [escribir-guia]',
      '    guide:',
      '      path: "docs/tests/probar-*.md"',
      '      agent: probador',
      '      skip: ["## 4."]',
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
  // El agente de implement corrigió en su sesión.
  expect(stages.find((s) => s.stageKey === 'implement')!.agents.map((a) => a.kind)).toEqual([
    'START',
    'RESUME',
  ])
  const judge = stages.find((s) => s.stageKey === 'guia')!.judge!
  expect([judge.status, judge.round, judge.passed]).toEqual(['PASSED', 2, true])
  expect(judge.guide!.guides).toEqual(['docs/tests/probar-suma.md'])
  expect(judge.guide!.steps.map((s) => s.status)).toEqual(['passed', 'passed', 'skipped'])

  await page.goto(`/runs/${runId}`)
  const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
  await expect(progress).toContainText('2 de 2 pasos pasan · 1 saltado')
  await progress.getByRole('button', { name: /^3\. guia/ }).click()
  const panel = page.getByRole('region', { name: 'Guía de pruebas de la fase' })
  await expect(panel).toContainText('docs/tests/probar-suma.md')
  await expect(panel).toContainText('ronda 2')
  await expect(panel.getByRole('list', { name: 'Pasos de ## 1. Tests' })).toContainText(
    'add() suma los negativos',
  )
  await expect(panel).toContainText('Saltado')
})
