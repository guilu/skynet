import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'

/** Lo que comparten build-map.ts y build-social.ts para pintar con Chromium. */

/** Barlow incrustada: las imágenes salen con la tipografía de la web en cualquier máquina. */
export async function barlowCss(): Promise<string> {
  const fonts = await Promise.all(
    [400, 600].map(async (w) => {
      const file = join(
        import.meta.dirname,
        `../node_modules/@fontsource/barlow/files/barlow-latin-${w}-normal.woff2`,
      )
      const data = (await readFile(file)).toString('base64')
      return `@font-face { font-family: Barlow; font-weight: ${w}; font-display: block; src: url(data:font/woff2;base64,${data}) format('woff2'); }`
    }),
  )
  return fonts.join('\n')
}

/** Chromium de Playwright, o el de SCREENSHOTS_CHROMIUM si se indica. */
export function launchChromium() {
  return chromium.launch(
    process.env.SCREENSHOTS_CHROMIUM ? { executablePath: process.env.SCREENSHOTS_CHROMIUM } : {},
  )
}
