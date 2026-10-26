// Iconos PNG de la web (pantalla de inicio de iOS y Android) y un WEBP para documentación, a partir
// de public/favicon.svg (Skyny en reposo), que sale de src/lib/brand.ts (lo comprueba un test).
// Volver a lanzarlo si cambia Skyny:
//
//   node scripts/iconos.mjs
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const pub = path.join(root, 'public')
const svg = fs.readFileSync(path.join(pub, 'favicon.svg'), 'utf8')

/** Fondo de la baldosa: el azul muy claro de la web, con un degradado suave. */
const TILE = 'linear-gradient(160deg, #f4f7ff, #d9e4ff)'

// iOS recorta las esquinas él mismo: su icono va a sangre. Android recorta el «maskable» con la
// forma del sistema, así que Skyny va dentro de la zona segura (el círculo del 80 %).
const ICONS = [
  { file: 'apple-touch-icon.png', size: 180, tile: 'bleed', scale: 0.78 },
  { file: 'icon-192.png', size: 192, tile: 'rounded', scale: 0.8 },
  { file: 'icon-512.png', size: 512, tile: 'rounded', scale: 0.8 },
  { file: 'icon-maskable-512.png', size: 512, tile: 'bleed', scale: 0.62 },
  { file: '../../docs/ui/skyny-512.webp', size: 512, tile: 'none', scale: 1, webp: true },
]

const browser = await chromium.launch()
for (const { file, size, tile, scale, webp } of ICONS) {
  const page = await browser.newPage({ viewport: { width: size, height: size } })
  const box =
    tile === 'none'
      ? ''
      : `background:${TILE};border-radius:${tile === 'rounded' ? Math.round(size * 0.22) : 0}px;`
  const inner = Math.round(size * scale)
  await page.setContent(
    `<body style="margin:0;background:transparent">` +
      `<div style="width:${size}px;height:${size}px;display:grid;place-items:center;${box}">` +
      svg.replace('<svg ', `<svg width="${inner}" height="${inner}" style="display:block" `) +
      '</div></body>',
  )
  const out = path.join(pub, file)
  const png = await page.screenshot({ omitBackground: true })
  if (webp) {
    // Playwright solo saca PNG o JPEG: el WEBP lo codifica el propio Chromium con un canvas.
    const data = await page.evaluate(async (b64) => {
      const img = new Image()
      img.src = `data:image/png;base64,${b64}`
      await img.decode()
      const canvas = document.createElement('canvas')
      canvas.width = img.width
      canvas.height = img.height
      canvas.getContext('2d').drawImage(img, 0, 0)
      return canvas.toDataURL('image/webp', 0.92).split(',')[1]
    }, png.toString('base64'))
    fs.writeFileSync(out, Buffer.from(data, 'base64'))
  } else {
    fs.writeFileSync(out, png)
  }
  await page.close()
  console.log(path.relative(root, out))
}
await browser.close()
