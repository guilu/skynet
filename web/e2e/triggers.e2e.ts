import { expect, test, type Page } from '@playwright/test'
import { createHmac, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { e2eApiUrl } from '../e2e-config.ts'
import { expectAccessible, login, postApi, publishWorkflowViaApi, repoPath } from './helpers.ts'

/**
 * Disparadores (DS-C), contra el GitHub falso de fake-github.ts: se encienden en Ajustes → GitHub;
 * una etiqueta en una issue lanza el workflow de su regla y el trabajo dice de qué issue viene; un
 * disparador programado se crea en la ficha del repositorio y «Disparar ya» lo lanza; y con una
 * sola ejecución a la vez, la segunda espera «En cola» hasta que la primera termina.
 */

test.use({ baseURL: 'http://localhost:4173' })

const secret = process.env.SKYNET_GITHUB_WEBHOOK_SECRET ?? 'e2e-webhook-secret'
const api = e2eApiUrl(process.env.E2E_API_PORT)
const github = `http://127.0.0.1:${process.env.E2E_GITHUB_PORT ?? 4200}`

/** Un workflow de una sola fase `command`, sin agentes: se lanza y termina enseguida. */
const yaml = (key: string, command = '"true"') =>
  [
    `id: ${key}`,
    `name: Tarea ${key}`,
    'stages:',
    '  - id: hacer',
    '    type: command',
    `    command: ${command}`,
    '',
  ].join('\n')

async function runStatus(page: Page, runId: string): Promise<string> {
  return (await (await page.request.get(`/api/workflow-runs/${runId}`)).json()).status
}

test('disparadores: etiqueta en GitHub, programado y cola por repositorio', async ({ page }) => {
  test.setTimeout(300_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  const suffix = Date.now().toString(36).slice(-6)
  const key = `ds${suffix}`
  await publishWorkflowViaApi(page, yaml(key))
  const slow = `ds${suffix}lento`
  await publishWorkflowViaApi(page, yaml(slow, '"sleep 20"'))
  const project = await postApi(page, '/api/projects', {
    key: `DS${suffix}`.toUpperCase(),
    name: 'E2E Disparadores',
  })
  const repository = await postApi(page, `/api/projects/${project.id}/repositories`, {
    name: 'demo-repo',
    localPath: repoPath,
    remoteUrl: 'https://github.com/guilu/demo-repo.git',
  })

  await test.step('se encienden en Ajustes → GitHub', async () => {
    await page.goto('/settings?tab=github')
    await page.getByRole('button', { name: 'Sincronizar' }).click()
    await expect(page.getByText('Sincronizado.')).toBeVisible()
    const section = page.getByRole('region', { name: 'Disparadores', exact: true })
    const on = section.getByRole('button', { name: 'Encender' })
    if (await on.isVisible()) await on.click()
    await expect(section.getByText('Activos')).toBeVisible()
    await expect(section.getByText(/Hoy van \d+ de \d+/)).toBeVisible()
    await expectAccessible(page, 'Ajustes → GitHub con los disparadores')
  })

  const label = `skynet:${key}`
  const issue = 7000 + Math.floor(Math.random() * 1000)
  await test.step('una etiqueta lanza el workflow y avisa en la issue', async () => {
    await postApi(page, `/api/repositories/${repository.id}/triggers`, {
      kind: 'ISSUE',
      workflowKey: key,
      label,
    })
    const fixture = JSON.parse(
      readFileSync(resolve('../fixtures/github/issues-labeled.json'), 'utf8'),
    )
    fixture.issue.number = issue
    fixture.issue.html_url = `https://github.com/guilu/demo-repo/issues/${issue}`
    fixture.issue.labels = [{ name: label }]
    fixture.label = { name: label }
    const body = JSON.stringify(fixture)
    const response = await page.request.post(`${api}/api/github/webhooks`, {
      data: body,
      headers: {
        'Content-Type': 'application/json',
        'X-GitHub-Event': 'issues',
        'X-GitHub-Delivery': randomUUID(),
        'X-Hub-Signature-256': `sha256=${createHmac('sha256', secret).update(body).digest('hex')}`,
      },
    })
    expect(response.status()).toBe(202)

    const row = page
      .getByRole('table', { name: /Últimas peticiones/ })
      .getByRole('row')
      .filter({ hasText: `guilu/demo-repo#${issue}` })
    // La lista se refresca cada 10 s.
    await expect(row.getByText(/Lanzada|Terminada/)).toBeVisible({ timeout: 30_000 })
    await row.getByRole('link', { name: 'Ver la ejecución' }).click()
    await page.locator('header.run-header').getByRole('link', { name: /^DS/ }).click()
    await expect(page.getByRole('link', { name: `Desde guilu/demo-repo#${issue}` })).toBeVisible()

    await expect
      .poll(
        async () => {
          const comments = await (await page.request.get(`${github}/_e2e/issues`)).json()
          return (comments[`guilu/demo-repo#${issue}`] ?? []).join('\n')
        },
        { timeout: 30_000 },
      )
      .toContain(key)
  })

  await test.step('un programado se crea en la ficha del repositorio y se dispara', async () => {
    await page.goto(`/projects/${project.id}?tab=repos`)
    const triggers = page.getByRole('region', { name: 'Disparadores de demo-repo' })
    await expect(triggers.getByText(label)).toBeVisible()
    await triggers.getByRole('button', { name: 'Nuevo disparador en demo-repo' }).click()
    const sheet = page.getByRole('dialog', { name: 'Nuevo disparador en demo-repo' })
    await sheet.getByLabel(/A una hora/).check()
    await sheet.getByRole('combobox', { name: /Workflow/ }).selectOption(key)
    await sheet.getByLabel(/Cuándo/).fill('0 0 3 * * *')
    await sheet.getByLabel(/Título del trabajo/).fill('Limpieza nocturna')
    await sheet.getByRole('button', { name: 'Crear disparador' }).click()
    await expect(sheet).toBeHidden()

    const item = triggers.getByRole('listitem').filter({ hasText: '0 0 3 * * *' })
    await expect(item.getByText(/Próxima:/)).toBeVisible()
    await expectAccessible(page, 'disparadores del repositorio')
    await item.getByRole('button', { name: 'Disparar ya' }).click()
    // Lo recoge el planificador (cada 15 s); la ficha no se refresca sola.
    await expect
      .poll(
        async () => {
          const rules = await (
            await page.request.get(`/api/repositories/${repository.id}/triggers`)
          ).json()
          return rules.find((r: { kind: string }) => r.kind === 'SCHEDULE')?.schedule?.lastOutcome
        },
        { timeout: 60_000 },
      )
      .toBe('LAUNCHED')
    await page.reload()
    await expect(item.getByText('lanzó')).toBeVisible()
    await item.getByRole('link', { name: 'ver' }).click()
    await expect(page.locator('header.run-header')).toContainText('Limpieza nocturna')
  })

  await test.step('con una a la vez, la segunda espera en cola', async () => {
    await page.goto(`/projects/${project.id}?tab=repos`)
    const queue = page.getByRole('region', { name: 'Ejecuciones a la vez en demo-repo' })
    await queue.getByRole('button', { name: /Cambiar las ejecuciones/ }).click()
    await queue.getByLabel(/Ejecuciones a la vez/).fill('1')
    await queue.getByRole('button', { name: 'Guardar' }).click()
    await expect(queue.getByText('propio')).toBeVisible()
    // Que no quede nada en marcha de los pasos anteriores.
    await expect
      .poll(
        async () =>
          (await (await page.request.get(`/api/repositories/${repository.id}/run-queue`)).json())
            .running,
        { timeout: 60_000 },
      )
      .toBe(0)

    const item = await postApi(page, `/api/projects/${project.id}/work-items`, {
      title: 'Dos a la vez',
      type: 'FEATURE',
    })
    const definitions = await (await page.request.get('/api/workflow-definitions')).json()
    const definitionId = definitions.find((d: { key: string }) => d.key === slow).id
    const launch = () =>
      postApi(page, `/api/work-items/${item.id}/runs`, {
        repositoryId: repository.id,
        definitionId,
      })
    const first = (await launch()).id
    const second = (await launch()).id
    expect(await runStatus(page, second)).toBe('PENDING')

    await page.goto(`/runs/${second}`)
    const header = page.locator('header.run-header')
    await expect(header.getByText(/En cola/)).toBeVisible()
    await expect(header.getByText(/puesto 1/)).toBeVisible()
    await expectAccessible(page, 'ejecución en cola')
    await expect.poll(() => runStatus(page, first), { timeout: 90_000 }).toBe('SUCCEEDED')
    await expect.poll(() => runStatus(page, second), { timeout: 30_000 }).not.toBe('PENDING')
    await expect(header.getByText(/puesto/)).toHaveCount(0, { timeout: 15_000 })
  })
})
