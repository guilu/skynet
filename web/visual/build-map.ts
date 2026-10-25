import { chromium } from '@playwright/test'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { capturedScreens, fileName, inventory } from './inventory.ts'
import { layoutMap, mapReadme, renderHtml, renderSvg, resolvedMap, THUMB_SIZE } from './map.ts'
import { galleryDir } from './paths.ts'

/**
 * Mapa de navegación (VS-B) a partir de las capturas de escritorio en claro que ya hay en la
 * galería: miniaturas, SVG portable, HTML navegable, PNG, PDF, JSON resuelto, submapas por grupo y
 * README, en <galería>/map. Lo llama scripts/screenshots.sh tras capturar; `npm run
 * screenshots:map [carpeta]` lo rehace sin volver a capturar.
 */
const dir = process.argv[2] ? resolve(process.argv[2]) : galleryDir
const mapDir = join(dir, 'map')
const { viewport, theme } = inventory.canonical
const screens = capturedScreens(inventory)
const thumbName = (s: (typeof screens)[number]) => fileName(s).replace(/\.png$/, '.jpg')

await rm(mapDir, { recursive: true, force: true })
await mkdir(join(mapDir, 'thumbs'), { recursive: true })
await mkdir(join(mapDir, 'groups'), { recursive: true })

// Barlow incrustada: el PNG y el PDF salen con la tipografía de la web en cualquier máquina.
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
const fontCss = fonts.join('\n')

const browser = await chromium.launch(
  process.env.SCREENSHOTS_CHROMIUM ? { executablePath: process.env.SCREENSHOTS_CHROMIUM } : {},
)
try {
  // 1. Miniaturas: la parte de arriba de cada captura, al doble de su tamaño en el mapa.
  const thumbs = new Map<string, string>()
  const page = await browser.newPage({ viewport: THUMB_SIZE })
  for (const s of screens) {
    const png = await readFile(join(dir, viewport, theme, fileName(s)))
    await page.setContent(
      `<body style="margin:0"><div style="width:100vw;height:100vh;background:#fff url(data:image/png;base64,${png.toString('base64')}) top center / 100% auto no-repeat"></div></body>`,
    )
    const jpg = await page.screenshot({ type: 'jpeg', quality: 82 })
    await writeFile(join(mapDir, 'thumbs', thumbName(s)), jpg)
    thumbs.set(s.id, `data:image/jpeg;base64,${jpg.toString('base64')}`)
  }

  // 2. Mapa completo: SVG portable (miniaturas incrustadas), HTML, JSON.
  const layout = layoutMap(inventory)
  const svg = renderSvg(layout, inventory, { image: (s) => thumbs.get(s.id)! })
  await writeFile(join(mapDir, 'navigation-map.svg'), svg)
  await writeFile(join(mapDir, 'navigation-map.html'), renderHtml(layout, inventory, fontCss))
  await writeFile(
    join(mapDir, 'navigation-map.json'),
    `${JSON.stringify(resolvedMap(inventory, layout), null, 2)}\n`,
  )

  // 3. PNG y PDF del mismo SVG, a tamaño real.
  const sheet = await browser.newPage({ viewport: { width: layout.width, height: layout.height } })
  await sheet.setContent(`<style>${fontCss} body { margin: 0 }</style>${svg}`)
  await sheet.evaluate('document.fonts.ready')
  await sheet.screenshot({ path: join(mapDir, 'navigation-map.png') })
  await sheet.pdf({
    path: join(mapDir, 'navigation-map.pdf'),
    width: `${layout.width}px`,
    height: `${layout.height}px`,
    printBackground: true,
    pageRanges: '1',
  })

  // 4. Un submapa por grupo.
  const groupFiles: { title: string; file: string }[] = []
  for (const [i, g] of inventory.groups.entries()) {
    if (!screens.some((s) => s.group === g.id)) continue
    const file = `${String(i + 1).padStart(2, '0')}-${g.id}.svg`
    const sub = layoutMap(inventory, g.id)
    await writeFile(
      join(mapDir, 'groups', file),
      renderSvg(sub, inventory, { image: (s) => thumbs.get(s.id)! }),
    )
    groupFiles.push({ title: g.title, file })
  }
  await writeFile(join(mapDir, 'README.md'), mapReadme(inventory, groupFiles))
  console.log(
    `Mapa listo en ${mapDir}: ${layout.nodes.length} pantallas, ${layout.edges.length} conectores, ${groupFiles.length} submapas (${layout.width} × ${layout.height})`,
  )
} finally {
  await browser.close()
}
