import { readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { checkCaptures, checkMap, checkZip, expectedFiles, zipFiles, ZIP_NAME } from './gallery.ts'
import { inventory, variants } from './inventory.ts'
import { galleryDir } from './paths.ts'

/**
 * Revisa una galería ya generada sin volver a capturar: `npm run screenshots:verify [carpeta]`.
 * Todas las capturas del manifiesto, ninguna de más, con su tamaño leído de la cabecera, el
 * mapa completo y coherente, el README y un ZIP con exactamente todo eso.
 */
const dir = process.argv[2] ? resolve(process.argv[2]) : galleryDir
const problems = [...(await checkCaptures(dir, inventory)), ...(await checkMap(dir, inventory, []))]
const readme = await readFile(join(dir, 'README.md'), 'utf8').catch(() => null)
if (readme == null) problems.push('falta README.md')
const zip = await readFile(join(dir, ZIP_NAME)).catch(() => null)
if (!zip) problems.push(`falta ${ZIP_NAME}`)
else problems.push(...checkZip(zip, inventory))
if (problems.length) {
  console.error(`${dir}: ${problems.length} problemas\n- ${problems.join('\n- ')}`)
  process.exit(1)
}
const perVariant = expectedFiles(inventory).length / variants(inventory).length
console.log(
  `${dir}: ${expectedFiles(inventory).length} PNG (${perVariant} en cada una de ${variants(
    inventory,
  )
    .map((v) => `${v.viewport}/${v.theme}`)
    .join(
      ', ',
    )}), tamaños correctos, mapa completo, README y ZIP con ${zipFiles(inventory).length} ficheros`,
)
