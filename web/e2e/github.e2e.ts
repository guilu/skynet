import { expect, test } from '@playwright/test'
import { createHmac, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { e2eApiUrl } from '../e2e-config.ts'
import { expectAccessible, login, postApi, repoPath } from './helpers.ts'

/**
 * GitHub conectado (GH-C), contra el GitHub falso de fake-github.ts: la App sale conectada en
 * Ajustes → GitHub, Sincronizar trae la instalación, un repositorio con URL de github.com sale «con
 * acceso», y una entrega firmada se ve en la lista de entregas, en Actividad y como evento.
 */

test.use({ baseURL: 'http://localhost:4173' })

const secret = process.env.SKYNET_GITHUB_WEBHOOK_SECRET ?? 'e2e-webhook-secret'
const api = e2eApiUrl(process.env.E2E_API_PORT)

test('Ajustes → GitHub: sincronizar, recibir una entrega firmada y verla en Actividad', async ({
  page,
}) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  const key = `GH${Date.now().toString(36).slice(-6)}`.toUpperCase()
  const project = await postApi(page, '/api/projects', { key, name: 'E2E GitHub' })
  const repository = await postApi(page, `/api/projects/${project.id}/repositories`, {
    name: 'demo-repo',
    localPath: repoPath,
    remoteUrl: 'https://github.com/Guilu/Demo-Repo.git',
  })

  await test.step('la App sale conectada y Sincronizar trae la instalación', async () => {
    await page.goto('/settings?tab=github')
    await expect(page.getByRole('tab', { name: 'GitHub', selected: true })).toBeVisible()
    await expect(page.getByText('Conectada')).toBeVisible()
    await expect(page.getByRole('link', { name: 'skynet-e2e' })).toBeVisible()
    await page.getByRole('button', { name: 'Sincronizar' }).click()
    await expect(page.getByText('Sincronizado.')).toBeVisible()
    await expect(page.getByText('guilu/demo-repo', { exact: true }).first()).toBeVisible()
    const row = page
      .getByRole('table', { name: /Repositorios de Skynet/ })
      .getByRole('row')
      .filter({ hasText: key })
    await expect(row.getByText('Con acceso')).toBeVisible()
  })

  const deliveryId = randomUUID()
  await test.step('una entrega firmada se acepta una vez y se procesa', async () => {
    const body = readFileSync(resolve('../fixtures/github/pull_request-opened.json'))
    const signature = `sha256=${createHmac('sha256', secret).update(body).digest('hex')}`
    const send = (sig: string) =>
      page.request.post(`${api}/api/github/webhooks`, {
        data: body,
        headers: {
          'Content-Type': 'application/json',
          'X-GitHub-Event': 'pull_request',
          'X-GitHub-Delivery': deliveryId,
          'X-Hub-Signature-256': sig,
        },
      })
    expect((await send('sha256=00')).status()).toBe(401)
    expect((await send(signature)).status()).toBe(202)
    expect((await send(signature)).status()).toBe(200)

    const deliveries = page.getByRole('table', { name: /Últimas entregas/ })
    const row = deliveries.getByRole('row').filter({ has: page.locator(`[title="${deliveryId}"]`) })
    // La lista se refresca cada 10 s.
    await expect(row.getByText('Procesada')).toBeVisible({ timeout: 30_000 })
    await expect(row.getByText('pull_request.opened')).toBeVisible()
    await expectAccessible(page, 'Ajustes → GitHub en claro')
    await page.emulateMedia({ colorScheme: 'dark' })
    // Que acaben las transiciones del cambio de tema; si no, axe puede esperarlas sin fin.
    await page.waitForTimeout(1000)
    await expectAccessible(page, 'Ajustes → GitHub en oscuro')
    await page.emulateMedia({ colorScheme: 'light' })
  })

  await test.step('el repositorio dice que tiene acceso y el evento llega a Actividad', async () => {
    const events = await (
      await page.request.get(`/api/events?aggregateId=${repository.id}&types=github.*`)
    ).json()
    expect(events.map((e: { type: string }) => e.type)).toEqual(['github.pull_request.opened'])
    expect(events[0].payload).toMatchObject({ deliveryId, number: 12, headRef: 'skynet/demo-7' })

    await page.goto(`/projects/${project.id}?tab=repos`)
    const github = page.getByRole('region', { name: 'GitHub de demo-repo' })
    await expect(github.getByText('Con acceso')).toBeVisible()
    await expect(github.getByText(/instalación de guilu/)).toBeVisible()
    await expectAccessible(page, 'repositorio con acceso a GitHub')

    await page.goto('/activity')
    await expect(
      page.getByText('GitHub guilu/demo-repo: PR #12 opened (Corrige add() con negativos)').first(),
    ).toBeVisible()
  })
})
