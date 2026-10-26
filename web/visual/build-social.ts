import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { capturedScreens, fileName, inventory } from './inventory.ts'
import { pngSize } from './png.ts'
import { galleryDir } from './paths.ts'
import { barlowCss, launchChromium } from './render.ts'
import { renderSocial, SOCIAL_FORMATS, socialFile, socialLayout } from './social.ts'

/**
 * Composiciones para redes (VS-C) a partir de las capturas de escritorio y móvil que ya hay en la
 * galería, en <galería>/social/<formato>/. Lo llama scripts/screenshots.sh tras capturar; `npm run
 * screenshots:social [carpeta]` las rehace sin volver a capturar.
 */
const dir = process.argv[2] ? resolve(process.argv[2]) : galleryDir
const socialDir = join(dir, 'social')
const screens = capturedScreens(inventory)
const fontCss = await barlowCss()
const logo = await readFile(join(import.meta.dirname, '../public/favicon.svg'), 'utf8')
const dataUri = (png: Buffer) => `data:image/png;base64,${png.toString('base64')}`

await rm(socialDir, { recursive: true, force: true })
const browser = await launchChromium()
try {
  for (const format of SOCIAL_FORMATS) {
    const { width, height } = inventory.social.formats[format]
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 })
    for (const pick of inventory.social.picks) {
      const screen = screens.find((s) => s.id === pick.screen)!
      const desktop = await readFile(join(dir, 'desktop', pick.theme, fileName(screen)))
      const mobile = await readFile(join(dir, 'mobile', pick.theme, fileName(screen)))
      const layout = socialLayout(inventory, format, pngSize(desktop).height)
      await page.setContent(
        renderSocial({
          inv: inventory,
          layout,
          pick,
          desktop: dataUri(desktop),
          mobile: dataUri(mobile),
          logo,
          fontCss,
        }),
      )
      await page.evaluate('document.fonts.ready')
      const out = join(dir, socialFile(inventory, format, pick))
      await mkdir(dirname(out), { recursive: true })
      await writeFile(out, await page.screenshot({ type: 'png' }))
    }
    await page.close()
  }
  const count = SOCIAL_FORMATS.length * inventory.social.picks.length
  console.log(`Composiciones listas en ${socialDir}: ${count} (${SOCIAL_FORMATS.join(', ')})`)
} finally {
  await browser.close()
}
