import { relative } from 'node:path'
import { expectedFiles, finishGallery } from './gallery.ts'
import { inventory } from './inventory.ts'
import { galleryDir, renderedDir } from './paths.ts'
import { machineSecrets } from './privacy.ts'

/**
 * Cierre de `npm run screenshots`: comprueba todas las capturas (nombres, tamaño, tema), escanea la
 * privacidad de lo capturado y solo entonces escribe el índice y el ZIP.
 */
const { problems, warnings, zipBytes } = await finishGallery({
  galleryDir,
  renderedDir,
  inv: inventory,
  secrets: machineSecrets(),
})
for (const w of warnings) console.warn(`Aviso: ${w}`)
if (problems.length) {
  console.error(
    `La galería tiene ${problems.length} problemas (no se empaqueta):\n- ${problems.join('\n- ')}`,
  )
  process.exit(1)
}
console.log(
  `Galería lista: ${expectedFiles(inventory).length} capturas comprobadas en ${relative(process.cwd(), galleryDir)}, ZIP de ${(zipBytes / 1e6).toFixed(1)} MB`,
)
