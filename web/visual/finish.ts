import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { capturedScreens, fileName, inventory, variants } from './inventory.ts'
import { galleryDir, renderedDir, zipName } from './paths.ts'
import { pngSize } from './png.ts'
import { machineSecrets, scanText, type Finding } from './privacy.ts'
import { galleryReadme } from './readme.ts'
import { buildZip } from './zip.ts'

/**
 * Cierre de `npm run screenshots`: comprueba que están todas las capturas con su tamaño, escanea
 * la privacidad de lo capturado, escribe el índice y el ZIP. Falla (y no empaqueta) ante cualquier
 * problema.
 */

async function walk(dir: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => [])
  const files = await Promise.all(
    entries.map((e) => (e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)])),
  )
  return files.flat()
}

const problems: string[] = []

// 1. Todas las capturas, ninguna sobrante, con el ancho del viewport.
const expected = new Set<string>()
for (const { viewport, theme } of variants()) {
  const size = inventory.viewports[viewport]
  for (const screen of capturedScreens()) {
    const path = join(galleryDir, viewport, theme, fileName(screen))
    expected.add(path)
    const data = await readFile(path).catch(() => null)
    if (!data) {
      problems.push(`falta ${relative(galleryDir, path)}`)
      continue
    }
    const { width, height } = pngSize(data)
    const tall = screen.capture === 'fullPage' ? height >= size.height : height === size.height
    if (width !== size.width || !tall)
      problems.push(`${relative(galleryDir, path)} mide ${width} × ${height}`)
  }
}
for (const file of await walk(galleryDir)) {
  if (file.endsWith('.png') && !expected.has(file) && !relative(galleryDir, file).includes('/map/'))
    await rm(file) // De un inventario anterior.
}

// 2. Privacidad: texto visible y API de cada captura.
const secrets = machineSecrets()
const findings: Finding[] = []
for (const file of await walk(renderedDir)) {
  const { screen, text, api } = JSON.parse(await readFile(file, 'utf8'))
  const where = `${relative(renderedDir, file).split('/')[0]}/${screen}`
  findings.push(...scanText(`${where} (texto)`, text, secrets))
  findings.push(...scanText(`${where} (API)`, JSON.stringify(api), secrets))
}

// 3. Índice, también escaneado.
const readme = galleryReadme(inventory)
findings.push(...scanText('README.md', readme, secrets))

if (findings.length) {
  const unique = [...new Map(findings.map((f) => [`${f.rule}|${f.match}`, f])).values()]
  for (const f of unique) problems.push(`privacidad: ${f.rule} «${f.match}» en ${f.where}`)
}
if (problems.length) {
  console.error(`La galería tiene ${problems.length} problemas:\n- ${problems.join('\n- ')}`)
  process.exit(1)
}

await mkdir(galleryDir, { recursive: true })
await writeFile(join(galleryDir, 'README.md'), readme)

// 4. ZIP: solo la galería (imágenes e índices), nunca lo temporal.
const zipped = (await walk(galleryDir))
  .filter((f) => !f.endsWith(zipName))
  .filter((f) => /\.(png|md|html|svg|pdf|json)$/.test(f))
const zip = buildZip(
  await Promise.all(
    zipped.map(async (f) => ({ name: relative(galleryDir, f), data: await readFile(f) })),
  ),
)
await writeFile(join(galleryDir, zipName), zip)
console.log(
  `Galería lista: ${expected.size} capturas en ${relative(process.cwd(), galleryDir)}, ZIP de ${(zip.length / 1e6).toFixed(1)} MB`,
)
