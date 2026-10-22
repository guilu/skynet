import { fireEvent, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import artifacts from '../../fixtures/contracts/artifacts.json'
import runView from '../../fixtures/contracts/run-view.json'
import runners from '../../fixtures/contracts/runners.json'
import verifications from '../../fixtures/contracts/verifications.json'
import App from './App'
import { declaredFilesOf, previewKind, safeLink } from './components/run/artifacts'
import type { ArtifactSummary } from './api'
import { mockFetch, renderAt } from './test/render'

// Monaco no funciona en jsdom: el visor se sustituye por un bloque con el mismo texto.
vi.mock('./components/run/MonacoView', () => ({
  default: ({ text, label }: { text: string; label: string }): ReactNode => (
    <pre aria-label={label}>{text}</pre>
  ),
}))

class SilentEventSource {
  onopen = null
  onerror = null
  onmessage = null
  close() {}
}

const runId = runView.id
const agent = runView.stages[0].agents[0]
const [diffArtifact, , declaredArtifact] = artifacts as ArtifactSummary[]

/** Un Markdown que intenta ejecutar código y salir de Skynet. */
const markdown = [
  '# Mapa del repositorio',
  '',
  'Texto **en negrita** y una tabla:',
  '',
  '| módulo | qué hace |',
  '| --- | --- |',
  '| web | la interfaz |',
  '',
  '<script>window.hacked = true</script>',
  '<img src="x" onerror="window.hacked = true">',
  '',
  '[peligroso](javascript:alert(1)) · [docs](https://example.com/docs) · ![logo](https://evil.example/t.png)',
  '',
].join('\n')
const markdownBytes = new TextEncoder().encode(markdown)

const declared = (id: string, name: string, mediaType: string, size: number, extra = {}) => ({
  ...declaredArtifact,
  id,
  name,
  mediaType,
  size,
  metadata: { path: name, glob: 'docs/**', mediaType, size },
  ...extra,
})

const files = [
  declared('md', 'docs/skynet/mapa.md', 'text/markdown', markdownBytes.length),
  declared('png', 'docs/img/grafo.png', 'image/png', 2048),
  declared('pdf', 'docs/plano.pdf', 'application/pdf', 4096),
  declared('zip', 'dist/app.zip', 'application/octet-stream', 9000),
  declared('big', 'docs/enorme.txt', 'text/plain', 30_000_000, { truncated: true }),
]

const content = (id: string, body: Uint8Array) => ({
  [`/api/artifacts/${id}/content?offset=0&limit=${body.length}`]: new Response(body as BodyInit, {
    headers: { 'X-Artifact-Size': String(body.length) },
  }),
})

function openRun(tab: string) {
  vi.stubGlobal('EventSource', SilentEventSource)
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      [`/api/workflow-runs/${runId}`]: runView,
      '/api/runners': runners,
      [`/api/agent-runs/${agent.id}/verifications`]: verifications,
      [`/api/agent-runs/${agent.id}/artifacts`]: [diffArtifact, ...files],
      ...content('md', markdownBytes),
    }),
  )
  vi.stubGlobal('fetch', fetch)
  renderAt(`/runs/${runId}?tab=${tab}`, <App />)
  return fetch
}

async function generatedFiles() {
  const panel = await screen.findByRole('tabpanel', { name: 'Artefactos' }, { timeout: 5000 })
  return within(panel).findByRole('list', { name: 'Archivos generados' })
}

describe('archivos generados', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lista solo los ficheros declarados, con su enlace de descarga', async () => {
    openRun('artifacts')
    const list = await generatedFiles()
    const items = within(list).getAllByRole('listitem')
    expect(items.map((i) => i.querySelector('.file-path')?.textContent?.trim())).toEqual([
      'dist/app.zip',
      'docs/enorme.txt',
      'docs/img/grafo.png',
      'docs/plano.pdf',
      'docs/skynet/mapa.md',
    ])
    // El diff (calc.py) no es un fichero declarado.
    expect(within(list).queryByText(/calc\.py/)).not.toBeInTheDocument()
    const download = within(list).getByRole('link', { name: 'Descargar docs/skynet/mapa.md' })
    expect(download).toHaveAttribute('href', '/api/artifacts/md/download')
    expect(download).toHaveAttribute('download', 'mapa.md')
    // Lo recortado no se descarga como si estuviera entero.
    expect(
      within(list).queryByRole('link', { name: 'Descargar docs/enorme.txt' }),
    ).not.toBeInTheDocument()
    expect(within(list).getByText(/recortado: no se puede descargar entero/)).toBeInTheDocument()
  })

  it('abre el Markdown sin ejecutar su HTML ni seguir enlaces peligrosos', async () => {
    openRun('artifacts')
    const list = await generatedFiles()
    fireEvent.click(within(list).getByRole('button', { name: 'Abrir docs/skynet/mapa.md' }))
    const doc = await within(list).findByRole('article', {
      name: 'Contenido de docs/skynet/mapa.md',
    })
    expect(within(doc).getByRole('heading', { name: 'Mapa del repositorio' })).toBeInTheDocument()
    expect(within(doc).getByRole('table')).toBeInTheDocument()
    expect(doc.querySelector('script, img, iframe')).toBeNull()
    expect(doc.innerHTML).not.toContain('onerror')
    expect(within(doc).getByText('peligroso').closest('a')).toBeNull()
    expect(within(doc).getByRole('link', { name: 'docs' })).toHaveAttribute(
      'href',
      'https://example.com/docs',
    )
    expect(within(doc).getByRole('link', { name: 'docs' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    )
    expect(within(doc).getByText('[imagen: logo]')).toBeInTheDocument()
    expect((window as { hacked?: boolean }).hacked).toBeUndefined()

    fireEvent.click(within(list).getByRole('button', { name: 'Ver como texto' }))
    expect(within(list).getByLabelText('Contenido de docs/skynet/mapa.md')).toHaveTextContent(
      '<script>window.hacked = true</script>',
    )
  })

  it('previsualiza las imágenes y deja los PDF y los binarios para descargar', async () => {
    openRun('artifacts')
    const list = await generatedFiles()
    fireEvent.click(within(list).getByRole('button', { name: 'Abrir docs/img/grafo.png' }))
    expect(await within(list).findByRole('img', { name: 'docs/img/grafo.png' })).toHaveAttribute(
      'src',
      '/api/artifacts/png/download',
    )
    expect(
      within(list).queryByRole('button', { name: 'Abrir docs/plano.pdf' }),
    ).not.toBeInTheDocument()
    expect(within(list).getByRole('link', { name: 'Descargar docs/plano.pdf' })).toBeInTheDocument()
    expect(within(list).getByText(/visor del navegador al descargarlo/)).toBeInTheDocument()
    expect(
      within(list).queryByRole('button', { name: 'Abrir dist/app.zip' }),
    ).not.toBeInTheDocument()
    expect(within(list).getByText(/Sin vista previa/)).toBeInTheDocument()
  })

  it('la verificación dice qué ficheros declarados no se guardaron', async () => {
    openRun('verification')
    const panel = await screen.findByRole('tabpanel', { name: 'Verificación' })
    const omitted = (
      await within(panel).findAllByRole('list', { name: 'No guardados de artifacts' })
    )[0]
    expect(omitted).toHaveTextContent(
      'docs/plano.pdf · no se guardó: Supera el máximo de 10 MB por fichero',
    )
  })
})

describe('reglas de los archivos generados', () => {
  it('se queda el de la última verificación de cada ruta', () => {
    const a = declared('a', 'docs/x.md', 'text/markdown', 1, { createdAt: '2026-10-05T09:00:00Z' })
    const b = declared('b', 'docs/x.md', 'text/markdown', 1, { createdAt: '2026-10-05T10:00:00Z' })
    expect(declaredFilesOf([a, diffArtifact, b]).map((f) => f.id)).toEqual(['b'])
  })

  it('solo previsualiza los tipos seguros', () => {
    expect(previewKind('text/markdown')).toBe('markdown')
    expect(previewKind('application/json')).toBe('json')
    expect(previewKind('text/plain;charset=UTF-8')).toBe('text')
    expect(previewKind('image/png')).toBe('image')
    // Un SVG puede llevar scripts: no se pinta.
    expect(previewKind('image/svg+xml')).toBe('none')
    expect(previewKind('application/pdf')).toBe('pdf')
    expect(previewKind('application/octet-stream')).toBe('none')
  })

  it('solo deja enlaces http, https y mailto', () => {
    expect(safeLink('https://example.com')).toBe('https://example.com')
    expect(safeLink('mailto:a@b.c')).toBe('mailto:a@b.c')
    expect(safeLink('javascript:alert(1)')).toBe('')
    expect(safeLink('JaVaScRiPt:alert(1)')).toBe('')
    expect(safeLink('data:text/html,<script>')).toBe('')
    expect(safeLink('../otro.md')).toBe('')
  })
})
