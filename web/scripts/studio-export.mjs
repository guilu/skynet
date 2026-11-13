// Exporta el estudio de Skyny 3D a UN solo HTML (three, código, estilos y fuentes dentro), para
// abrirlo con doble clic, enseñarlo o adjuntarlo sin `npm run dev`. Es una salida generada: no se
// edita; los cambios se hacen en src/components/companion/three/ (ver docs/ui/skyny-3d.md).
//
//   npm run studio:export        →  dist-studio/skyny-studio.html
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const outDir = path.join(root, 'dist-studio')

await build({
  root,
  logLevel: 'warn',
  publicDir: false,
  build: {
    outDir,
    emptyOutDir: true,
    // Todo dentro: fuentes e imágenes como data: y un único trozo de JS y de CSS.
    assetsInlineLimit: () => true,
    cssCodeSplit: false,
    modulePreload: false,
    chunkSizeWarningLimit: 4000,
    rolldownOptions: {
      input: path.join(root, 'skyny-studio.html'),
      output: { codeSplitting: false },
    },
  },
})

const file = path.join(outDir, 'skyny-studio.html')
let html = fs.readFileSync(file, 'utf8')
const read = (src) => fs.readFileSync(path.join(outDir, src.replace(/^\//, '')), 'utf8')
// `</script` dentro del código cerraría la etiqueta antes de tiempo.
html = html.replace(
  /<script type="module" crossorigin src="([^"]+)"><\/script>/g,
  (_, src) => `<script type="module">${read(src).replaceAll('</script', '<\\/script')}</script>`,
)
html = html.replace(
  /<link rel="stylesheet" crossorigin href="([^"]+)">/g,
  (_, href) => `<style>${read(href)}</style>`,
)
if (/(src|href)="\/assets\//.test(html))
  throw new Error('Ha quedado algún fichero sin meter dentro')
fs.writeFileSync(file, html)
fs.rmSync(path.join(outDir, 'assets'), { recursive: true, force: true })
const kb = Math.round(fs.statSync(file).size / 1024)
console.log(`Estudio exportado: ${path.relative(process.cwd(), file)} (${kb} KB)`)
