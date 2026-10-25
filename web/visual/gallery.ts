import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { capturedScreens, fileName, variants, type Inventory } from './inventory.ts'
import { pngSize } from './png.ts'
import { scanText } from './privacy.ts'
import { galleryReadme } from './readme.ts'
import { buildZip, zipEntries } from './zip.ts'

/**
 * Comprobaciones y empaquetado de la galería, sin navegador: las usan finish.ts (al terminar de
 * capturar), verify.ts (sobre una galería ya generada) y los tests.
 */

export const ZIP_NAME = 'skynet-screenshots.zip'

/** Lo que deja cada captura en el directorio de texto renderizado. */
export interface Rendered {
  screen: string
  viewport: string
  theme: string
  /** Tema que se ve: `data-theme` de `<html>` o, sin él, la preferencia del sistema. */
  dataTheme: string | null
  innerWidth: number
  text: string
  api: unknown[]
}

/** Rutas relativas de todas las capturas que pide el inventario, en orden estable. */
export function expectedFiles(inv: Inventory): string[] {
  return variants(inv).flatMap(({ viewport, theme }) =>
    capturedScreens(inv).map((s) => `${viewport}/${theme}/${fileName(s)}`),
  )
}

async function walk(dir: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => [])
  const files = await Promise.all(
    entries.map((e) => (e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)])),
  )
  return files.flat()
}

/** Todas las capturas, ninguna de más, con el ancho del viewport y el alto que toca. */
export async function checkCaptures(galleryDir: string, inv: Inventory): Promise<string[]> {
  const problems: string[] = []
  const expected = new Set(expectedFiles(inv))
  const screens = new Map(capturedScreens(inv).map((s) => [fileName(s), s]))
  for (const rel of expected) {
    const [viewport, , name] = rel.split('/') as [keyof Inventory['viewports'], string, string]
    const data = await readFile(join(galleryDir, rel)).catch(() => null)
    if (!data) {
      problems.push(`falta ${rel}`)
      continue
    }
    let size: { width: number; height: number }
    try {
      size = pngSize(data)
    } catch {
      problems.push(`${rel} no es un PNG`)
      continue
    }
    const view = inv.viewports[viewport]
    const fullPage = screens.get(name)!.capture === 'fullPage'
    const tall = fullPage ? size.height >= view.height : size.height === view.height
    if (size.width !== view.width || !tall)
      problems.push(`${rel} mide ${size.width} × ${size.height}`)
  }
  for (const file of await walk(galleryDir)) {
    const rel = relative(galleryDir, file)
    if (/^(desktop|mobile)\/.*\.png$/.test(rel) && !expected.has(rel)) problems.push(`sobra ${rel}`)
  }
  return problems
}

/** Borra las capturas de un inventario anterior (las que ya no están en el manifiesto). */
export async function removeStale(galleryDir: string, inv: Inventory) {
  const expected = new Set(expectedFiles(inv))
  for (const file of await walk(galleryDir)) {
    const rel = relative(galleryDir, file)
    if (/^(desktop|mobile)\/.*\.png$/.test(rel) && !expected.has(rel)) await rm(file)
  }
}

/** Cada captura dejó su texto con el tema y el ancho pedidos, y nada de lo pintado es privado. */
export async function checkRendered(
  renderedDir: string,
  inv: Inventory,
  secrets: string[],
  warnings: string[] = [],
): Promise<string[]> {
  const problems: string[] = []
  for (const { viewport, theme } of variants(inv)) {
    for (const s of capturedScreens(inv)) {
      const where = `${viewport}-${theme}/${s.id}`
      const raw = await readFile(join(renderedDir, `${viewport}-${theme}`, `${s.id}.json`), 'utf8')
        .then((t) => JSON.parse(t) as Rendered)
        .catch(() => null)
      if (!raw) {
        problems.push(`falta el texto capturado de ${where}`)
        continue
      }
      if (raw.dataTheme !== theme) problems.push(`${where}: tema ${raw.dataTheme ?? 'ninguno'}`)
      if (raw.innerWidth < inv.viewports[viewport].width)
        problems.push(`${where}: ancho de ventana ${raw.innerWidth}`)
      // Más ancha que el viewport: la página desborda en horizontal. No es un fallo de la galería
      // (la captura mide lo que debe) sino del diseño; se avisa para el rediseño.
      if (raw.innerWidth > inv.viewports[viewport].width)
        warnings.push(`${where}: la página desborda en horizontal (${raw.innerWidth} px)`)
      const found = [
        ...scanText(`${where} (texto)`, raw.text, secrets),
        ...scanText(`${where} (API)`, JSON.stringify(raw.api), secrets),
      ]
      for (const f of found) problems.push(`privacidad: ${f.rule} «${f.match}» en ${f.where}`)
    }
  }
  return [...new Set(problems)]
}

/** El ZIP lleva exactamente las capturas y el README, nada más. */
export function checkZip(zip: Buffer, inv: Inventory): string[] {
  const names = zipEntries(zip)
  const expected = [...expectedFiles(inv), 'README.md'].sort()
  const problems: string[] = []
  for (const n of names) if (!expected.includes(n)) problems.push(`el ZIP lleva ${n}`)
  for (const n of expected) if (!names.includes(n)) problems.push(`al ZIP le falta ${n}`)
  if (names.join('\n') !== [...names].sort().join('\n')) problems.push('el ZIP no está ordenado')
  return problems
}

/**
 * Comprueba capturas y texto, y solo si todo está bien escribe el README y el ZIP. Devuelve los
 * problemas (vacío si la galería está lista).
 */
export async function finishGallery(options: {
  galleryDir: string
  renderedDir: string
  inv: Inventory
  secrets: string[]
}): Promise<{ problems: string[]; warnings: string[]; zipBytes: number }> {
  const { galleryDir, renderedDir, inv, secrets } = options
  const warnings: string[] = []
  await removeStale(galleryDir, inv)
  const readme = galleryReadme(inv)
  const problems = [
    ...(await checkCaptures(galleryDir, inv)),
    ...(await checkRendered(renderedDir, inv, secrets, warnings)),
    ...scanText('README.md', readme, secrets).map(
      (f) => `privacidad: ${f.rule} «${f.match}» en ${f.where}`,
    ),
  ]
  if (problems.length) {
    await rm(join(galleryDir, ZIP_NAME), { force: true })
    return { problems, warnings, zipBytes: 0 }
  }
  await mkdir(galleryDir, { recursive: true })
  await writeFile(join(galleryDir, 'README.md'), readme)
  const files = [...expectedFiles(inv), 'README.md']
  const zip = buildZip(
    await Promise.all(
      files.map(async (name) => ({ name, data: await readFile(join(galleryDir, name)) })),
    ),
  )
  const zipProblems = checkZip(zip, inv)
  if (zipProblems.length) return { problems: zipProblems, warnings, zipBytes: 0 }
  await writeFile(join(galleryDir, ZIP_NAME), zip)
  return { problems: [], warnings, zipBytes: zip.length }
}
