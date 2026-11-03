import { writeFileSync } from 'node:fs'
import { expect, test, type Page } from '@playwright/test'
import { csrf, login, postApi, publishWorkflowViaApi, workItemViaApi } from './helpers.ts'

/**
 * Proveedor de reserva (CQ-B): fake-claude se queda sin cuota una vez (el fichero testigo
 * FAKE_CLAUDE_QUOTA de scripts/e2e-service.sh) y el agente tiene Codex de reserva. Con «cambiar a
 * la reserva» a 0 minutos, la fase no espera: fake-codex sigue en el mismo worktree hasta terminar
 * y la página dice a quién sustituye.
 */

test.use({ baseURL: 'http://localhost:4173' })

const out = process.env.E2E_OUT ?? '/tmp'

interface RunStage {
  stageKey: string
  attempt: number
  status: string
  quotaWait: unknown
  agents: {
    provider: string
    failure: string | null
    fallback: { provider: string; resetsAt: string | null } | null
  }[]
}

async function run(page: Page, runId: string) {
  return (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()) as {
    status: string
    stages: RunStage[]
  }
}

async function fallbackAfter(page: Page, minutes: number) {
  const response = await page.request.put('/api/settings/quota', {
    data: { fallbackAfterMinutes: minutes },
    headers: await csrf(page),
  })
  expect(response.ok()).toBe(true)
}

test('un agente sin cuota pasa a su reserva en el mismo worktree', async ({ page }) => {
  test.setTimeout(240_000)
  // La ventana se renueva en 20 s: lo justo para no bloquear las E2E siguientes.
  writeFileSync(`${out}/claude-sin-cuota`, '20')

  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  await fallbackAfter(page, 0)
  try {
    const key = `reserva-${Date.now().toString(36)}`
    const definitionId = await publishWorkflowViaApi(
      page,
      [
        `id: ${key}`,
        'agents:',
        '  desarrollador:',
        '    prompt: Arregla add()',
        '    fallback:',
        '      provider: codex',
        'stages:',
        '  - id: implement',
        '    type: agent',
        '    agent: desarrollador',
        '',
      ].join('\n'),
    )
    const { workItemId, repositoryId } = await workItemViaApi(page)
    const { id: runId } = await postApi(page, `/api/work-items/${workItemId}/runs`, {
      repositoryId,
      definitionId,
    })

    await expect
      .poll(async () => (await run(page, runId)).status, { timeout: 120_000, intervals: [1_000] })
      .toBe('SUCCEEDED')
    const stages = (await run(page, runId)).stages
    expect(
      stages.map((s) => [s.attempt, s.status, s.agents.map((a) => [a.provider, a.failure])]),
    ).toEqual([
      [1, 'FAILED', [['claude-code', 'quota']]],
      [2, 'SUCCEEDED', [['codex', null]]],
    ])
    // No esperó: siguió la reserva.
    expect(stages[1].quotaWait).toBeNull()
    expect(stages[1].agents[0].fallback?.provider).toBe('claude-code')

    await page.goto(`/runs/${runId}`)
    const progress = page.getByRole('navigation', { name: 'Fases y agentes' })
    await expect(progress).toContainText('Sustituido por Codex (Claude Code sin cuota')

    // Que la cuenta de Claude Code vuelva a tener cuota antes de la siguiente E2E.
    const resetsAt = Date.parse(stages[1].agents[0].fallback!.resetsAt!)
    await page.waitForTimeout(Math.max(0, resetsAt - Date.now() + 1_000))
  } finally {
    await fallbackAfter(page, 15)
  }
})
