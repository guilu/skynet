import { expect, test, type Page } from '@playwright/test'
import {
  expectAccessible,
  login,
  openTab,
  postApi,
  publishWorkflowViaApi,
  workItemViaApi,
} from './helpers.ts'

/**
 * Memoria del proyecto (ME-D), criterio de aceptación: una ejecución guarda una decisión con
 * `mem_save`, otra ejecución nueva del mismo proyecto la recibe en su prompt y la encuentra con
 * `mem_search`, y la web enseña las dos cosas: la entrada (con su procedencia y la bitácora de cada
 * ejecución) en la pestaña Memoria del proyecto, y en el detalle del agente lo que guardó uno y lo
 * que recibió y buscó el otro.
 *
 * El runner de las E2E lanza fake-claude con `FAKE_MCP_CALLS=@.memoria.json`: el agente hace con
 * el servidor de memoria del runner las llamadas que la fase `guion` dejó en ese fichero del
 * worktree.
 */

test.use({ baseURL: 'http://localhost:4173' })

/** Un comando deja el guion de llamadas MCP y un agente (fake-claude) lo ejecuta. */
const yaml = (key: string, calls: unknown[]) => `id: ${key}
name: Memoria E2E
description: El agente llama a la memoria con un guion.

stages:
  - id: guion
    type: command
    command: |
      cat > .memoria.json <<'JSON'
      ${JSON.stringify(calls)}
      JSON
  - id: agente
    type: agent
    prompt: Usa la memoria del proyecto
    dependsOn: [guion]
`

interface RunView {
  status: string
  stages: { stageKey: string; agents: { id: string }[] }[]
}

async function launchAndFinish(
  page: Page,
  workItemId: string,
  repositoryId: string,
  definitionId: string,
) {
  const run = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    definitionId,
  })
  let view: RunView | null = null
  await expect
    .poll(
      async () => {
        view = await (await page.request.get(`/api/workflow-runs/${run.id}`)).json()
        return view!.status
      },
      { timeout: 180_000, intervals: [2_000] },
    )
    .toBe('SUCCEEDED')
  const agent = view!.stages.find((s) => s.stageKey === 'agente')!.agents[0].id
  return { runId: run.id as string, agentId: agent }
}

test('una ejecución guarda una decisión, otra nueva la encuentra y la web enseña las dos', async ({
  page,
}) => {
  test.setTimeout(300_000)
  const suffix = Date.now().toString(36)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const projectId = (await (await page.request.get(`/api/work-items/${workItemId}`)).json())
    .projectId as string

  const save = await publishWorkflowViaApi(
    page,
    yaml(`mem-guardar-${suffix}`, [
      {
        tool: 'mem_save',
        arguments: {
          kind: 'DECISION',
          title: 'Fusionar con squash',
          topic_key: 'git/merge',
          what: 'Las PRs van con squash para que main quede limpio.',
        },
      },
    ]),
  )
  const search = await publishWorkflowViaApi(
    page,
    yaml(`mem-buscar-${suffix}`, [{ tool: 'mem_search', arguments: { query: 'squash' } }]),
  )

  const first = await launchAndFinish(page, workItemId, repositoryId, save)
  const second = await launchAndFinish(page, workItemId, repositoryId, search)

  await test.step('la pestaña Memoria del proyecto enseña la decisión y las bitácoras', async () => {
    await page.goto(`/projects/${projectId}?tab=memory`)
    const timeline = page.getByRole('list', { name: 'Memoria del proyecto' })
    const decision = timeline.getByRole('button', { name: /Fusionar con squash/ })
    await expect(decision).toBeVisible()
    await expect(decision).toContainText('de un agente')
    await expect(timeline.getByRole('button', { name: /terminada bien/ })).toHaveCount(2)
    await expectAccessible(page, 'la memoria del proyecto')

    await decision.click()
    const sheet = page.getByRole('dialog', { name: 'Entrada de la memoria' })
    await expect(sheet.getByRole('heading', { name: 'Fusionar con squash' })).toBeVisible()
    await expect(sheet.getByRole('link', { name: 'Ver la ejecución' })).toHaveAttribute(
      'href',
      `/runs/${first.runId}`,
    )
    await expect(
      sheet.getByText('Las PRs van con squash para que main quede limpio.').first(),
    ).toBeVisible()
    await expectAccessible(page, 'una entrada de la memoria')
  })

  await test.step('el agente que la guardó lo dice en su apartado Memoria', async () => {
    await page.goto(`/runs/${first.runId}?agent=${first.agentId}`)
    await openTab(page, 'Memoria')
    const saved = page.getByRole('region', { name: 'Lo que guardó' })
    await expect(saved.getByRole('link', { name: 'Fusionar con squash' })).toBeVisible()
    await expect(saved).toContainText('v1 · con mem_save')
  })

  await test.step('el de la otra ejecución la recibió en el prompt y la encontró', async () => {
    await page.goto(`/runs/${second.runId}?agent=${second.agentId}`)
    await openTab(page, 'Memoria')
    const received = page.getByRole('region', { name: 'Lo que recibió' })
    await expect(received.getByRole('link', { name: 'Fusionar con squash' })).toBeVisible()
    await expect(received).toContainText('en el bloque del prompt')
    const searched = page.getByRole('region', { name: 'Lo que buscó o leyó' })
    await expect(searched).toContainText('Fusionar con squash')
    await expect(searched).toContainText('en una búsqueda')
    await expectAccessible(page, 'la memoria de un agente')

    await received.getByRole('link', { name: 'Fusionar con squash' }).click()
    await expect(page.getByRole('dialog', { name: 'Entrada de la memoria' })).toBeVisible()
  })
})
