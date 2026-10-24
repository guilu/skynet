import { AxeBuilder } from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'

/** Utilidades compartidas por las E2E; los servicios los arranca `scripts/e2e.sh`. */
export const repoPath = process.env.E2E_REPO_PATH ?? '/tmp/skynet-e2e-repo'
const adminUser = process.env.SKYNET_ADMIN_USER ?? 'admin'
const adminSecret = process.env.SKYNET_ADMIN_PASSWORD ?? 'e2e-admin'

/** Entra con el usuario de la web desde la página en la que esté el login. */
export async function login(page: Page, password = adminSecret) {
  await page.getByLabel('Usuario').fill(adminUser)
  await page.getByLabel('Contraseña').fill(password)
  await page.getByRole('button', { name: 'Entrar' }).click()
}

/** Cabecera CSRF para las llamadas a la API con la sesión del navegador. */
export async function csrf(page: Page): Promise<Record<string, string>> {
  const cookie = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')
  return cookie ? { 'X-XSRF-TOKEN': cookie.value } : {}
}

/** Procesos de fake-claude vivos en esta máquina. */
export function fakeClaudeProcesses(): string[] {
  try {
    return execFileSync('pgrep', ['-fa', 'dev[.]skynet[.]fakeclaude'], { encoding: 'utf8' })
      .split('\n')
      .filter(Boolean)
  } catch {
    return [] // pgrep sale con 1 si no encuentra ninguno.
  }
}

/** POST a la API con la sesión del navegador; devuelve el JSON de la respuesta. */
export async function postApi(page: Page, path: string, data: unknown) {
  const response = await page.request.post(path, { data, headers: await csrf(page) })
  expect(response.ok(), `POST ${path}: ${response.status()}`).toBe(true)
  return response.json()
}

let projectsCreated = 0

/**
 * Crea proyecto, repositorio (con comando de verificación si se pide) y trabajo. Devuelve los ids
 * del trabajo y del repositorio.
 */
export async function workItemViaApi(
  page: Page,
  options: { validationCommand?: string } = {},
): Promise<{ workItemId: string; repositoryId: string }> {
  // La hora no basta: dos llamadas en paralelo (usage.e2e.ts) caen en el mismo milisegundo.
  const seq = (projectsCreated++ % 36).toString(36)
  const key = `C${Date.now().toString(36).slice(-6)}${seq}`.toUpperCase()
  const project = await postApi(page, '/api/projects', { key, name: 'E2E por API' })
  const repo = await postApi(page, `/api/projects/${project.id}/repositories`, {
    name: 'demo',
    localPath: repoPath,
    validationCommand: options.validationCommand,
  })
  const item = await postApi(page, `/api/projects/${project.id}/work-items`, {
    title: 'Trabajo por API',
    type: 'BUG',
  })
  return { workItemId: item.id, repositoryId: repo.id }
}

/**
 * Crea proyecto, repositorio (con comando de verificación si se pide) y trabajo, y lanza un agente.
 * Devuelve el id de la ejecución.
 */
export async function launchViaApi(
  page: Page,
  prompt: string,
  options: { validationCommand?: string } = {},
): Promise<string> {
  const { workItemId, repositoryId } = await workItemViaApi(page, options)
  const run = await postApi(page, `/api/work-items/${workItemId}/runs`, { repositoryId, prompt })
  return run.id
}

/** Crea un workflow con ese YAML y publica su versión 1; devuelve el id de la versión. */
export async function publishWorkflowViaApi(page: Page, sourceYaml: string): Promise<string> {
  const draft = await postApi(page, '/api/workflows', { sourceYaml })
  await postApi(page, `/api/workflow-versions/${draft.id}/publish`, { revision: draft.revision })
  return draft.id
}

/** Para o arranca el control plane o el runner de las E2E (scripts/e2e-service.sh). */
export function service(action: 'start' | 'stop' | 'kill', name: 'control-plane' | 'runner') {
  execFileSync(resolve(import.meta.dirname, '../../scripts/e2e-service.sh'), [action, name], {
    stdio: 'ignore',
    timeout: 120_000,
  })
}

/** Reglas WCAG 2.1 A y AA. */
export async function expectAccessible(page: Page, what: string) {
  // Menús y diálogos entran con una animación de opacidad: a medias, axe mide un contraste falso.
  // (Las e2e no cargan los tipos del DOM: va como texto.)
  await page.evaluate(`Promise.all(
    document.getAnimations()
      .filter((a) => a.effect?.getComputedTiming().iterations !== Infinity)
      .map((a) => a.finished.catch(() => undefined)),
  )`)
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()
  const summary = results.violations.map(
    (v) =>
      `${v.id} (${v.impact}): ${v.help} → ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`,
  )
  expect(summary, `${what}: problemas de accesibilidad`).toEqual([])
}

/**
 * Abre una pestaña de la ejecución, también si el detalle del agente la tiene en su menú «Más». Lo
 * reintenta: el reparto entre las pestañas y el menú se vuelve a medir cuando cambia el ancho del
 * inspector (p. ej. al cargar su contenido), y una pestaña puede pasar de un sitio al otro a mitad.
 */
export async function openTab(page: Page, name: string) {
  const inspector = page.getByRole('region', { name: 'Inspector del agente' })
  await expect(inspector.getByRole('tab').first()).toBeVisible()
  const tab = page.getByRole('tab', { name, exact: true })
  await expect(async () => {
    if (await tab.count()) {
      if ((await tab.getAttribute('aria-selected')) !== 'true') await tab.click({ timeout: 2_000 })
    } else {
      await inspector.locator('[role=tab][aria-haspopup=menu]').click({ timeout: 2_000 })
      const item = page.getByRole('menuitemradio', { name, exact: true })
      try {
        await item.click({ timeout: 2_000 })
      } catch (e) {
        await page.keyboard.press('Escape')
        throw e
      }
    }
    await expect(
      page.getByRole('tab', { name: new RegExp(`^${name}`), selected: true }),
    ).toBeVisible({
      timeout: 2_000,
    })
  }).toPass({ timeout: 30_000 })
}
