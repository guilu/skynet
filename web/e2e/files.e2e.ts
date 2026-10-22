import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import {
  expectAccessible,
  login,
  openTab,
  postApi,
  publishWorkflowViaApi,
  workItemViaApi,
} from './helpers.ts'

/**
 * Archivos generados (AG-B, #145): lo que una fase declara en `artifacts` sale en la pestaña
 * Artefactos, se abre dentro de Skynet sin ejecutar su HTML y se descarga entero, también después
 * de limpiar el worktree. Lo que la fase cambia sin declararlo no sale.
 *
 * Una fase `command` hace de agente: escribe el Markdown declarado y una nota sin declarar, y
 * confirma los dos.
 */

test.use({ baseURL: 'http://localhost:4173' })

/** En ASCII: el `sh` del runner puede no tener un locale UTF-8. */
const MARKDOWN = [
  '# Mapa del repositorio',
  '',
  '| modulo | que hace |',
  '| --- | --- |',
  '| calc | suma |',
  '',
  '<script>window.hacked = true</script>',
  '',
].join('\n')

function yaml(key: string) {
  const write = [
    'mkdir -p docs/skynet',
    // `printf` convierte los `\n`; el texto no lleva comillas simples ni `%`.
    `printf '${MARKDOWN.replaceAll('\n', '\\n')}' > docs/skynet/mapa.md`,
    'echo sin declarar > nota.txt',
    'git add -A',
    'git -c user.email=e2e@skynet -c user.name=e2e commit -qm mapa',
  ].join(' && ')
  return [
    `id: ${key}`,
    'stages:',
    '  - id: mapa',
    '    type: command',
    `    command: ${JSON.stringify(write)}`,
    '    artifacts: [docs/skynet/mapa.md]',
    '',
  ].join('\n')
}

test('un fichero declarado se abre y se descarga, también sin worktree', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  await login(page)
  await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
  const definitionId = await publishWorkflowViaApi(page, yaml(`ag-${Date.now().toString(36)}`))
  const { workItemId, repositoryId } = await workItemViaApi(page)
  const { id: runId } = await postApi(page, `/api/work-items/${workItemId}/runs`, {
    repositoryId,
    definitionId,
  })

  await page.goto(`/runs/${runId}`)
  const header = page.locator('header.run-header')
  await expect(header.locator('.hero-top').getByText('Completada')).toBeVisible({
    timeout: 120_000,
  })
  await openTab(page, 'Artefactos')
  const list = page.getByRole('list', { name: 'Archivos generados' })

  await test.step('solo sale lo declarado', async () => {
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await expect(list).toContainText('docs/skynet/mapa.md')
    await expect(list).not.toContainText('nota.txt')
  })

  await test.step('el Markdown se abre formateado y sin ejecutar su HTML', async () => {
    await list.getByRole('button', { name: 'Abrir docs/skynet/mapa.md' }).click()
    const doc = list.getByRole('article', { name: 'Contenido de docs/skynet/mapa.md' })
    await expect(doc.getByRole('heading', { name: 'Mapa del repositorio' })).toBeVisible()
    await expect(doc.getByRole('table')).toBeVisible()
    await expect(doc.locator('script')).toHaveCount(0)
    expect(await page.evaluate('window.hacked')).toBeUndefined()
    await expectAccessible(page, 'archivos generados con la vista previa abierta')
  })

  const download = list.getByRole('link', { name: 'Descargar docs/skynet/mapa.md' })
  const downloaded = async () => {
    const [file] = await Promise.all([page.waitForEvent('download'), download.click()])
    expect(file.suggestedFilename()).toBe('mapa.md')
    return readFile((await file.path())!, 'utf8')
  }

  await test.step('se descarga entero', async () => {
    expect(await downloaded()).toBe(MARKDOWN)
  })

  await test.step('la descarga sigue tras limpiar el worktree', async () => {
    await postApi(page, `/api/workflow-runs/${runId}/workspaces/cleanup`, {})
    await expect
      .poll(
        async () =>
          (await (await page.request.get(`/api/workflow-runs/${runId}/deletion-preview`)).json())
            .liveWorkspaces,
        { timeout: 60_000, intervals: [1_000] },
      )
      .toBe(0)
    await page.reload()
    await openTab(page, 'Artefactos')
    expect(await downloaded()).toBe(MARKDOWN)
  })
})
