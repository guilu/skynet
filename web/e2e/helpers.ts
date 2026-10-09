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

/**
 * Crea proyecto, repositorio (con comando de verificación si se pide) y trabajo, y lanza un agente.
 * Devuelve el id de la ejecución.
 */
export async function launchViaApi(
  page: Page,
  prompt: string,
  options: { validationCommand?: string } = {},
): Promise<string> {
  const key = `C${Date.now().toString(36).toUpperCase().slice(-6)}`
  const headers = await csrf(page)
  const post = async (path: string, data: unknown) =>
    (await page.request.post(path, { data, headers })).json()
  const project = await post('/api/projects', { key, name: 'E2E por API' })
  const repo = await post(`/api/projects/${project.id}/repositories`, {
    name: 'demo',
    localPath: repoPath,
    validationCommand: options.validationCommand,
  })
  const item = await post(`/api/projects/${project.id}/work-items`, {
    title: 'Trabajo por API',
    type: 'BUG',
  })
  const run = await post(`/api/work-items/${item.id}/runs`, { repositoryId: repo.id, prompt })
  return run.id
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
