import { useQuery } from '@tanstack/react-query'
import { ChevronRight, Download, FileText } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, type ArtifactSummary } from '../../api'
import { formatBytes } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { MAX_CHUNK, previewKind, safeLink, type PreviewKind } from './artifacts'
import { CodeView } from './CodeView'
import { Button } from '../ui/Button'
import { LinesSkeleton } from '../ui/Skeleton'

const baseName = (path: string) => path.slice(path.lastIndexOf('/') + 1)

/**
 * Los ficheros que la fase declaró en `artifacts` (#145), aparte de los cambios de git y los logs:
 * se abren dentro de Skynet cuando hay una vista segura y se descargan siempre que se guardaron
 * enteros.
 */
export function GeneratedFiles({ files }: { files: ArtifactSummary[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (files.length === 0) return null
  return (
    <>
      <h3>Archivos generados</h3>
      <ul className="log-list generated-files small" aria-label="Archivos generados">
        {files.map((f) => {
          const kind = previewKind(f.mediaType)
          const previewable = kind !== 'none' && kind !== 'pdf' && f.size <= MAX_CHUNK
          return (
            <li key={f.id}>
              {previewable ? (
                <Button
                  variant="link"
                  aria-expanded={open === f.id}
                  aria-label={`Abrir ${f.name}`}
                  onClick={() => setOpen(open === f.id ? null : f.id)}
                >
                  <ChevronRight
                    className="log-chevron"
                    size={16}
                    strokeWidth={2.75}
                    aria-hidden="true"
                  />
                  <FileText size={16} strokeWidth={2.5} aria-hidden="true" />
                  <span className="file-path">{f.name}</span>
                </Button>
              ) : (
                <span className="file-path">
                  <FileText size={16} strokeWidth={2.5} aria-hidden="true" /> {f.name}
                </span>
              )}{' '}
              <span className="muted">
                {formatBytes(f.size)} · {f.mediaType}
              </span>{' '}
              {f.truncated ? (
                <span className="muted">· recortado: no se puede descargar entero</span>
              ) : (
                <a
                  className="btn btn-secondary btn-sm"
                  href={api.artifactDownloadUrl(f.id)}
                  download={baseName(f.name)}
                  aria-label={`Descargar ${f.name}`}
                >
                  <Download size={14} strokeWidth={2.5} aria-hidden="true" /> Descargar
                </a>
              )}
              {!previewable && <NoPreview kind={kind} size={f.size} />}
              {open === f.id && <Preview file={f} kind={kind} />}
            </li>
          )
        })}
      </ul>
    </>
  )
}

function NoPreview({ kind, size }: { kind: PreviewKind; size: number }) {
  let text: string
  if (kind === 'pdf') text = 'Se abre con el visor del navegador al descargarlo.'
  else if (kind !== 'none' && size > MAX_CHUNK) text = 'Demasiado grande para verlo aquí.'
  else text = 'Sin vista previa: descárgalo para abrirlo.'
  return <p className="muted small">{text}</p>
}

/** Los bytes de un artefacto pequeño (hasta {@link MAX_CHUNK}). */
function useArtifactBytes(file: ArtifactSummary) {
  return useQuery({
    queryKey: ['artifact-bytes', file.id],
    queryFn: async () => (await api.artifactChunk(file.id, 0, Math.max(file.size, 1))).bytes,
    // El contenido de un artefacto no cambia nunca.
    staleTime: Infinity,
  })
}

function Preview({ file, kind }: { file: ArtifactSummary; kind: PreviewKind }) {
  if (kind === 'image') return <ImagePreview file={file} />
  return <TextPreview file={file} kind={kind} />
}

function TextPreview({ file, kind }: { file: ArtifactSummary; kind: PreviewKind }) {
  const bytes = useArtifactBytes(file)
  if (bytes.error) return <ErrorMessage error={bytes.error} />
  if (!bytes.data) return <LinesSkeleton label={file.name} lines={3} />
  const text = new TextDecoder().decode(bytes.data)
  if (kind === 'markdown') return <MarkdownPreview file={file} text={text} />
  return (
    <div className="log-viewer">
      <CodeView
        text={text}
        language={kind === 'json' ? 'json' : 'plaintext'}
        label={`Contenido de ${file.name}`}
        height={320}
      />
    </div>
  )
}

/**
 * Markdown sin HTML: `react-markdown` no interpreta el HTML crudo (y `skipHtml` lo quita), los
 * enlaces solo pueden ser `http`, `https` o `mailto` y las imágenes no se cargan, para que un
 * fichero del repositorio no ejecute nada ni haga peticiones fuera de Skynet.
 */
function MarkdownPreview({ file, text }: { file: ArtifactSummary; text: string }) {
  const [plain, setPlain] = useState(false)
  return (
    <div className="generated-preview">
      <p className="small">
        <Button variant="link" onClick={() => setPlain(!plain)}>
          {plain ? 'Ver formateado' : 'Ver como texto'}
        </Button>
      </p>
      {plain ? (
        <CodeView
          text={text}
          language="plaintext"
          label={`Contenido de ${file.name}`}
          height={320}
        />
      ) : (
        <article className="markdown" aria-label={`Contenido de ${file.name}`}>
          <Markdown
            remarkPlugins={[remarkGfm]}
            skipHtml
            urlTransform={safeLink}
            components={{
              a: ({ href, children }) =>
                href ? (
                  <a href={href} target="_blank" rel="noopener noreferrer">
                    {children}
                  </a>
                ) : (
                  <span>{children}</span>
                ),
              img: ({ alt }): ReactNode => <span className="muted">[imagen: {alt}]</span>,
            }}
          >
            {text}
          </Markdown>
        </article>
      )}
    </div>
  )
}

/**
 * La imagen se pide a la descarga, del propio origen y con su tipo (solo PNG, JPEG, GIF y WebP): la
 * CSP no necesita `blob:` y no hace falta tener sus bytes en memoria.
 */
function ImagePreview({ file }: { file: ArtifactSummary }) {
  return (
    <div className="generated-preview">
      <img className="generated-image" src={api.artifactDownloadUrl(file.id)} alt={file.name} />
    </div>
  )
}
