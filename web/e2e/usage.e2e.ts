import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expectAccessible, login, postApi, workItemViaApi } from './helpers.ts'

/**
 * Uso de los CLIs (CU-C), criterio de aceptación del hito: con la cuenta de Codex al 88 %, un
 * agente de Codex la sube 3 puntos (fake-codex con FAKE_CODEX_QUOTA_STEP=3,1) y se le atribuyen;
 * la página Uso enseña los medidores con lo de Skynet y lo de fuera, y el aviso de que la ventana
 * pasa del 90 %. Un agente de Claude Code (fake-claude, cuota fija) llena la tarjeta de Claude.
 *
 * Lo que lleva gastado la cuenta de fake-codex está en $E2E_OUT/codex/fake-quota: lo lee también
 * la consulta del runner (app-server), así que escribirlo y pulsar «Actualizar» fija la cuota.
 */

test.use({ baseURL: 'http://localhost:4173' })

const codexHome = resolve(
  process.env.E2E_OUT ?? resolve(import.meta.dirname, '../../build/e2e'),
  'codex',
)

interface Window {
  provider: string
  window: string
  usedFraction: number
  skynet: number
}

interface Share {
  provider: string
  window: string
  share: number
  method: string
}

/** Fija lo que lleva gastado la cuenta de Codex y espera a que el runner lo consulte. */
async function setCodexQuota(page: Page, fiveHour: number, sevenDay: number) {
  mkdirSync(codexHome, { recursive: true })
  writeFileSync(resolve(codexHome, 'fake-quota'), `${fiveHour},${sevenDay}`)
  await postApi(page, '/api/usage/refresh', {})
  await expect
    .poll(async () => (await codexWindow(page, 'five_hour'))?.usedFraction, {
      timeout: 90_000,
      intervals: [2_000],
    })
    .toBeCloseTo(fiveHour / 100, 4)
}

async function windows(page: Page): Promise<Window[]> {
  const body = await (await page.request.get('/api/usage/windows')).json()
  return body.runners.flatMap((r: { windows: Window[] }) => r.windows)
}

async function codexWindow(page: Page, window: string) {
  return (await windows(page)).find((w) => w.provider === 'codex' && w.window === window)
}

/** Lanza un agente suelto con ese proveedor y espera a que termine con su cuota atribuida. */
async function runAgent(page: Page, provider: string): Promise<{ id: string; quota: Share[] }> {
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const run = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    prompt: 'Revisa calc.py',
    provider,
  })
  let quota: Share[] = []
  await expect
    .poll(
      async () => {
        const view = await (await page.request.get(`/api/workflow-runs/${run.id}`)).json()
        quota = view.totals.quota
        return view.status
      },
      { timeout: 120_000, intervals: [2_000] },
    )
    .toBe('SUCCEEDED')
  return { id: run.id, quota }
}

const pct = (fraction: number) => {
  const v = fraction * 100
  return `${v.toLocaleString('es-ES', { maximumFractionDigits: v >= 10 ? 0 : v >= 1 ? 1 : 2 })} %`
}

test('la página Uso reparte la cuota de Codex y avisa al pasar del 90 %', async ({ page }) => {
  test.setTimeout(420_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()

  await test.step('la cuenta de Codex empieza al 88 %', () => setCodexQuota(page, 88, 40))

  const [codex] = await test.step('un agente de Codex y otro de Claude Code', () =>
    Promise.all([runAgent(page, 'codex'), runAgent(page, 'claude-code')]))

  await test.step('al de Codex se le atribuye la subida de la cuota', async () => {
    await expect
      .poll(async () => {
        const view = await (await page.request.get(`/api/workflow-runs/${codex.id}`)).json()
        return view.totals.quota
          .filter((s: Share) => s.provider === 'codex')
          .map((s: Share) => [s.window, Math.round(s.share * 1000) / 1000, s.method])
      })
      .toEqual([
        ['five_hour', 0.03, 'measured'],
        ['seven_day', 0.01, 'measured'],
      ])
    await expect.poll(async () => (await codexWindow(page, 'five_hour'))?.usedFraction).toBe(0.91)
  })

  await test.step('los medidores enseñan lo de Skynet y lo de fuera', async () => {
    const window = (await codexWindow(page, 'five_hour'))!
    expect(window.skynet).toBeGreaterThanOrEqual(0.03 - 1e-9)
    await page.goto('/usage')
    await expect(page.getByRole('heading', { level: 1, name: 'Uso de los CLIs' })).toBeVisible()
    const card = page.getByRole('region', { name: 'Codex' })
    await expect(card.getByText('Casi agotada')).toBeVisible()
    const meter = card.getByRole('meter', { name: 'Codex, ventana de 5 h' })
    await expect(meter).toHaveAttribute('aria-valuenow', '91')
    await expect(meter).toHaveAttribute(
      'aria-valuetext',
      `91 % usado: Skynet ${pct(window.skynet)}, fuera ${pct(0.91 - window.skynet)}`,
    )
    await expect(card.getByText(`Skynet ${pct(window.skynet)}`).first()).toBeVisible()
    await expect(card.getByText(/de la última ejecución/)).toBeVisible()
    // fake-claude da siempre la misma cuota grabada, con ventanas que ya se han renovado.
    const claude = page.getByRole('region', { name: 'Claude Code' })
    await expect(claude.getByRole('meter', { name: 'Claude Code, semana' })).toBeVisible()
    await expect(claude.getByText(/de la última ejecución/)).toBeVisible()

    const attention = page.getByRole('region', { name: /Necesita tu atención/ })
    await expect(
      attention.getByRole('link', { name: /Codex al 91 % de la ventana de 5 h.*Casi agotada/ }),
    ).toBeVisible()

    const costs = page.getByRole('region', { name: 'Qué cuesta cada agente' })
    await expect(costs.getByRole('row', { name: /agent.*Codex/ })).toBeVisible()
    await expect(costs.locator(`a[href="/runs/${codex.id}"]`)).toContainText('3 % de 5 h')
    await expectAccessible(page, 'página Uso')
  })

  await test.step('la ejecución y su agente enseñan su cuota', async () => {
    await page.goto(`/runs/${codex.id}`)
    const kpi = page.locator('.run-kpis .kpi').filter({ hasText: 'Cuota' })
    await expect(kpi).toContainText('3 % de 5 h')
    await expect(kpi).toContainText('Codex: 3 % de 5 h · 1 % semanal')
    await page.getByRole('tab', { name: 'Coste' }).click()
    const inspector = page.getByRole('region', { name: 'Inspector del agente' })
    await expect(inspector.getByRole('heading', { name: 'Cuota' })).toBeVisible()
    await expect(inspector.getByText('3 % (medido)')).toBeVisible()
  })

  await test.step('el dashboard avisa y lleva a la página Uso', async () => {
    await page.goto('/')
    const attention = page.getByRole('region', { name: /Necesita tu atención/ })
    const warning = attention.getByRole('link', { name: /Codex al 91 % de la ventana de 5 h/ })
    await expect(warning).toHaveAttribute('href', '/usage')
  })

  // Las pruebas que vengan después no deben ver el aviso.
  await test.step('la cuenta vuelve a una cuota baja', () => setCodexQuota(page, 10, 5))
})
