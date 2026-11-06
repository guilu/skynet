import { expect, test, type Page } from '@playwright/test'
import { createHmac, randomInt, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { e2eApiUrl } from '../e2e-config.ts'
import {
  csrf,
  expectAccessible,
  login,
  postApi,
  publishWorkflowViaApi,
  repoPath,
} from './helpers.ts'

/**
 * Despliegues (DL-B a DL-E) y su disparador (DL-D), con el GitHub falso: un despliegue llega por
 * los webhooks `deployment` y `deployment_status`, Skynet comprueba su salud contra su propio
 * `/actuator/health`, la pestaña Despliegues lo enseña «Desplegado» y «Sano», y una regla «al
 * quedar sano» lanza su workflow una sola vez, aunque la entrega se repita.
 */

test.use({ baseURL: 'http://localhost:4173' })

const secret = process.env.SKYNET_GITHUB_WEBHOOK_SECRET ?? 'e2e-webhook-secret'
const api = e2eApiUrl(process.env.E2E_API_PORT)

/** Un workflow de una sola fase `command`, sin agentes. */
const yaml = (key: string) =>
  [
    `id: ${key}`,
    `name: Humo ${key}`,
    'stages:',
    '  - id: probar',
    '    type: command',
    '    command: "true"',
    '',
  ].join('\n')

/** Entrega firmada; una entrega repetida (mismo id) responde 200 y no hace nada. */
async function deliver(
  page: Page,
  event: string,
  payload: unknown,
  delivery = randomUUID(),
  expected = 202,
) {
  const body = JSON.stringify(payload)
  const response = await page.request.post(`${api}/api/github/webhooks`, {
    data: body,
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': event,
      'X-GitHub-Delivery': delivery,
      'X-Hub-Signature-256': `sha256=${createHmac('sha256', secret).update(body).digest('hex')}`,
    },
  })
  expect(response.status()).toBe(expected)
}

test('despliegues: llega de GitHub, queda sano, se ve y dispara una vez', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  const suffix = Date.now().toString(36).slice(-6)
  const key = `dl${suffix}`
  const environment = `e2e-${suffix}`
  await publishWorkflowViaApi(page, yaml(key))
  const project = await postApi(page, '/api/projects', {
    key: `DL${suffix}`.toUpperCase(),
    name: 'E2E Despliegues',
  })
  const repository = await postApi(page, `/api/projects/${project.id}/repositories`, {
    name: 'demo-repo',
    localPath: repoPath,
    remoteUrl: 'https://github.com/guilu/demo-repo.git',
  })
  await postApi(page, '/api/github/sync', {})
  const settings = await page.request.put('/api/triggers/settings', {
    data: { enabled: true },
    headers: await csrf(page),
  })
  expect(settings.ok()).toBe(true)

  // La comprobación de salud apunta al propio control plane, sin esperas.
  const check = await page.request.put(
    `/api/repositories/${repository.id}/health-checks/${environment}`,
    {
      data: { url: `${api}/actuator/health`, initialDelaySeconds: 0, attempts: 3 },
      headers: await csrf(page),
    },
  )
  expect(check.ok()).toBe(true)
  const rule = await postApi(page, `/api/repositories/${repository.id}/triggers`, {
    kind: 'DEPLOYMENT',
    workflowKey: key,
    environment,
    health: 'HEALTHY',
    enabled: true,
  })

  const sha = randomUUID().replaceAll('-', '').padEnd(40, 'a').slice(0, 40)
  const deploymentId = randomInt(1_000_000, 2_000_000_000)
  const created = JSON.parse(
    readFileSync(resolve('../fixtures/github/deployment-created.json'), 'utf8'),
  )
  created.deployment = { ...created.deployment, id: deploymentId, sha, environment }
  const status = JSON.parse(
    readFileSync(resolve('../fixtures/github/deployment_status-created.json'), 'utf8'),
  )
  status.deployment = created.deployment
  status.deployment_status = {
    ...status.deployment_status,
    id: deploymentId + 1,
    state: 'success',
    environment,
  }
  const statusDelivery = randomUUID()

  await test.step('el despliegue llega por webhook y queda sano', async () => {
    await deliver(page, 'deployment', created)
    await deliver(page, 'deployment_status', status, statusDelivery)
    await expect
      .poll(
        async () => {
          const envs = await (
            await page.request.get(`/api/repositories/${repository.id}/environments`)
          ).json()
          const env = envs.find((e: { environment: string }) => e.environment === environment)
          return env?.deployed ? `${env.deployed.status}/${env.deployed.health}` : null
        },
        { timeout: 60_000 },
      )
      .toBe('SUCCEEDED/HEALTHY')
  })

  await test.step('la pestaña Despliegues lo enseña desplegado y sano', async () => {
    await page.goto(`/projects/${project.id}?tab=deployments`)
    const card = page.getByRole('article', { name: `Entorno ${environment}` })
    await expect(card.getByText(sha.slice(0, 7))).toBeVisible()
    await expect(card.getByText('Desplegado', { exact: true })).toBeVisible()
    await expect(card.getByText('Sano', { exact: true })).toBeVisible()
    await card.getByRole('button', { name: `Detalle de lo desplegado en ${environment}` }).click()
    const sheet = page.getByRole('dialog', { name: 'Despliegue' })
    await expect(sheet.getByText(/Intento 1: Bien/)).toBeVisible()
    await expectAccessible(page, 'detalle de un despliegue')
    await page.keyboard.press('Escape')
  })

  await test.step('el disparador lanza su workflow una sola vez', async () => {
    // Lo recoge la pasada de los disparadores por despliegue (cada 15 s).
    await expect
      .poll(
        async () =>
          (await (await page.request.get(`/api/triggers/${rule.id}`)).json()).deployment
            ?.lastOutcome,
        { timeout: 90_000 },
      )
      .toBe('LAUNCHED')
    const fired = (await (await page.request.get(`/api/triggers/${rule.id}`)).json()).deployment
    // Una entrega repetida no cambia nada: ni otro despliegue ni otro lanzamiento.
    await deliver(page, 'deployment_status', status, statusDelivery, 200)
    await deliver(page, 'deployment_status', status)
    await page.waitForTimeout(20_000)
    const after = (await (await page.request.get(`/api/triggers/${rule.id}`)).json()).deployment
    expect(after.lastRunId).toBe(fired.lastRunId)
    const runs = await (await page.request.get(`/api/work-items/${fired.workItemId}/runs`)).json()
    expect(runs).toHaveLength(1)

    await page.goto(`/projects/${project.id}?tab=config`)
    const triggers = page.getByRole('region', { name: 'Disparadores de demo-repo' })
    const item = triggers.getByRole('listitem').filter({ hasText: environment })
    await expect(item.getByText(/Al quedar sano/)).toBeVisible()
    await expect(item.getByText('lanzó')).toBeVisible()
    await expectAccessible(page, 'disparador por despliegue')
  })
})
