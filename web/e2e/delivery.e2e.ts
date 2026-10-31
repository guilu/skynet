import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { join } from 'node:path'
import { expectAccessible, login, postApi, publishWorkflowViaApi, repoPath } from './helpers.ts'

/**
 * Entrega por PR de punta a punta (EN-C), contra el GitHub falso de fake-github.ts y un remoto
 * local: una fase `command` arregla add(), `pr` sube la rama y abre la PR, `ci` espera su CI (un
 * check `test` en verde), la puerta enseña la PR y su CI, y al aprobar `fusionar` la fusiona con el
 * commit aprobado y borra la rama. El trabajo pasa a hecho. Nada sale a GitHub.
 *
 * La ronda de corrección de una CI roja la cubre `CiCheckIT`: aquí haría falta un agente que cree
 * commits nuevos al seguir su sesión.
 */

test.use({ baseURL: 'http://localhost:4173' })

const github = `http://127.0.0.1:${process.env.E2E_GITHUB_PORT ?? 4200}`
const remote = join(process.env.E2E_GITHUB_REMOTES ?? '', 'guilu/demo-repo.git')

function yaml(key: string) {
  return `id: entrega-${key.toLowerCase()}
name: Entrega por PR (E2E)
stages:
  - id: arreglar
    type: command
    command: "sed -i 's/a - b/a + b/' calc.py && git -c user.email=e2e@skynet -c user.name=e2e commit -qam 'Suma en add()'"
  - id: pr
    type: github-pr
    dependsOn: [arreglar]
    condition: repository.github
  - id: ci
    type: github-check
    dependsOn: [pr]
    condition: repository.github
  - id: aprobar-fusion
    type: human-approval
    dependsOn: [ci]
    condition: repository.github
    action: "Fusionar la PR de {{workItem.key}}"
    risk: high
  - id: fusionar
    type: github-merge
    dependsOn: [aprobar-fusion]
    condition: repository.github
`
}

async function stageStatus(page: Page, runId: string, stageKey: string): Promise<string> {
  const run = await (await page.request.get(`/api/workflow-runs/${runId}`)).json()
  return run.stages.find((s: { stageKey: string }) => s.stageKey === stageKey)?.status
}

async function themes(page: Page, what: string) {
  await expectAccessible(page, `${what} en claro`)
  await page.emulateMedia({ colorScheme: 'dark' })
  // Que acaben las transiciones del cambio de tema; si no, axe puede esperarlas sin fin.
  await page.waitForTimeout(1000)
  await expectAccessible(page, `${what} en oscuro`)
  await page.emulateMedia({ colorScheme: 'light' })
}

test('Entrega por PR: abrir, esperar la CI, aprobar la fusión y fusionar', async ({ page }) => {
  test.setTimeout(300_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  const key = `EN${Date.now().toString(36).slice(-6)}`.toUpperCase()
  const project = await postApi(page, '/api/projects', { key, name: 'E2E entrega por PR' })
  const repository = await postApi(page, `/api/projects/${project.id}/repositories`, {
    name: 'demo-repo',
    localPath: repoPath,
    remoteUrl: 'https://github.com/guilu/demo-repo.git',
  })
  await postApi(page, '/api/github/sync', {})
  const item = await postApi(page, `/api/projects/${project.id}/work-items`, {
    title: 'Arreglar add()',
    type: 'BUG',
  })
  const definitionId = await publishWorkflowViaApi(page, yaml(key))
  const run = await postApi(page, `/api/work-items/${item.id}/runs`, {
    repositoryId: repository.id,
    definitionId,
    inputs: {},
  })
  const branch = `skynet/${item.key.toLowerCase()}`

  let approvalId = ''
  await test.step('la PR se abre, la CI pasa y la puerta pregunta', async () => {
    await expect
      .poll(() => stageStatus(page, run.id, 'aprobar-fusion'), {
        timeout: 180_000,
        intervals: [2_000],
      })
      .toBe('WAITING_FOR_APPROVAL')
    const approvals = await (await page.request.get('/api/approvals?status=PENDING')).json()
    approvalId = approvals.find((a: { workflowRunId: string }) => a.workflowRunId === run.id).id

    await page.goto(`/runs/${run.id}`)
    const card = page.getByRole('region', { name: 'Pull request' })
    await expect(card.getByRole('link', { name: /^PR #\d+$/ })).toBeVisible()
    await expect(card).toContainText('Abierta')
    await expect(card).toContainText(`${branch} → main`)
    await expect(card).toContainText('En verde')
    await expect(card.getByRole('list', { name: 'Checks de la CI' })).toContainText('test')
    await themes(page, 'la ejecución con su PR')
  })

  await test.step('la aprobación enseña la PR y su CI, y aprobar fusiona', async () => {
    await page.goto(`/approvals/${approvalId}`)
    const evidence = page.getByRole('region', { name: 'La PR que se fusiona al aprobar' })
    await expect(evidence.getByRole('link', { name: /^PR #\d+$/ })).toBeVisible()
    await expect(evidence).toContainText(branch)
    await expect(evidence).toContainText('CI: en verde')
    await expect(evidence.getByRole('list', { name: 'Checks de la CI' })).toContainText('test')
    await themes(page, 'la aprobación de la fusión')

    await page.getByRole('button', { name: 'Aprobar' }).click()
    await page.getByRole('button', { name: 'Sí, aprobar' }).click()
    await expect(page.getByText('Aprobada', { exact: true })).toBeVisible()
    await expect
      .poll(
        async () => (await (await page.request.get(`/api/workflow-runs/${run.id}`)).json()).status,
        { timeout: 120_000, intervals: [2_000] },
      )
      .toBe('SUCCEEDED')
  })

  await test.step('la PR queda fusionada con el commit aprobado, sin rama, y el trabajo hecho', async () => {
    const pulls = await (await page.request.get(`${github}/_e2e/pulls`)).json()
    const pr = pulls['guilu/demo-repo'].find(
      (p: { head: { ref: string } }) => p.head.ref === branch,
    )
    expect(pr).toMatchObject({ merged: true, merge_method: 'squash', deletedBranch: true })
    expect(() =>
      execFileSync('git', ['--git-dir', remote, 'rev-parse', '--verify', `refs/heads/${branch}`], {
        stdio: 'ignore',
      }),
    ).toThrow()
    const merges = await (await page.request.get(`/api/workflow-runs/${run.id}/merges`)).json()
    expect(merges[0]).toMatchObject({ status: 'MERGED', expectedSha: pr.head.sha })
    const workItem = await (await page.request.get(`/api/work-items/${item.id}`)).json()
    expect(workItem.status).toBe('CLOSED')

    await page.goto(`/runs/${run.id}?view=events`)
    const card = page.getByRole('region', { name: 'Pull request' })
    await expect(card).toContainText('Fusionada (eeeeeee)')
    await expect(
      page.getByText(`fusionar fusionó la PR #${pr.number} y borró la rama ${branch}`).first(),
    ).toBeVisible()
    await themes(page, 'la ejecución con la PR fusionada')
  })
})
