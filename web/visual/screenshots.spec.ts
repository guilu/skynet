import { expect, test } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { capturedScreens, fileName, type ThemeId, type ViewportId } from './inventory.ts'
import { fixtureFile, galleryDir, renderedDir, type Fixture } from './paths.ts'
import { PREPARERS } from './screens.ts'

/**
 * Captura cada pantalla del inventario en la combinación de este proyecto (`desktop-light`…).
 * Además de la imagen, guarda el texto visible y las respuestas JSON de la API, que escanea
 * finish.ts antes de empaquetar.
 */

let fx: Fixture

test.beforeAll(async () => {
  fx = JSON.parse(await readFile(fixtureFile, 'utf8'))
})

for (const screen of capturedScreens()) {
  test(fileName(screen), async ({ page }, info) => {
    const [viewport, theme] = info.project.name.split('-') as [ViewportId, ThemeId]
    const errors: string[] = []
    const api: unknown[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    page.on('response', async (r) => {
      if (!r.url().includes('/api/')) return
      if (r.status() >= 500) errors.push(`${r.status()} ${r.url()}`)
      if (!(r.headers()['content-type'] ?? '').includes('application/json')) return
      api.push(await r.json().catch(() => null))
    })

    // Tema y reloj antes de cargar nada.
    await page.addInitScript((t) => localStorage.setItem('skynet.theme', t), theme)
    // El reloj arranca en la misma hora en cada captura y avanza solo: parado del todo, las
    // animaciones de las gráficas (que miden el tiempo con Date) no terminarían nunca.
    await page.clock.install({ time: fx.frozenAt })

    await PREPARERS[screen.id](page, { fx, viewport, theme })

    // Fuentes cargadas, animaciones finitas terminadas, sin cursor ni scroll suave.
    await page.evaluate(`document.fonts.ready`)
    await page.evaluate(`Promise.all(
      document.getAnimations()
        .filter((a) => a.effect?.getComputedTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => undefined)),
    )`)
    await page.addStyleTag({
      content:
        '*, *::before, *::after { caret-color: transparent !important; } html { scroll-behavior: auto !important; }',
    })

    const out = join(galleryDir, viewport, theme, fileName(screen))
    await mkdir(dirname(out), { recursive: true })
    await page.screenshot({
      path: out,
      fullPage: screen.capture === 'fullPage',
      animations: 'disabled',
      caret: 'hide',
    })

    const text = await page.evaluate(`document.body.innerText`)
    const rendered = join(renderedDir, info.project.name, `${screen.id}.json`)
    await mkdir(dirname(rendered), { recursive: true })
    await writeFile(rendered, JSON.stringify({ screen: screen.id, text, api }))

    expect(errors, `${screen.id}: errores en la página`).toEqual([])
  })
}
