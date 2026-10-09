// Iconos PNG de la web (pantalla de inicio de iOS y Android) a partir de public/favicon.svg, que
// sale de src/lib/brand.ts (lo comprueba un test). Volver a lanzarlo si cambia el isotipo:
//
//   node scripts/iconos.mjs
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const pub = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../public')
const svg = fs.readFileSync(path.join(pub, 'favicon.svg'), 'utf8')
const primary = svg.match(/rx="18" fill="(#[0-9a-f]{6})"/i)[1]

// iOS recorta las esquinas él mismo: su icono va a sangre, con el acento hasta el borde.
const ICONS = [
  { file: 'apple-touch-icon.png', size: 180, bleed: true },
  { file: 'icon-192.png', size: 192, bleed: false },
  { file: 'icon-512.png', size: 512, bleed: false },
]

const browser = await chromium.launch()
for (const { file, size, bleed } of ICONS) {
  const page = await browser.newPage({ viewport: { width: size, height: size } })
  const background = bleed ? primary : 'transparent'
  await page.setContent(
    `<body style="margin:0;background:${background}">` +
      svg.replace('<svg ', `<svg width="${size}" height="${size}" style="display:block" `) +
      '</body>',
  )
  await page.screenshot({ path: path.join(pub, file), omitBackground: !bleed })
  await page.close()
  console.log(file)
}
await browser.close()
