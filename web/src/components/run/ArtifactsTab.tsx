import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { api, type AgentRun, type ArtifactSummary } from '../../api'
import { formatBytes, formatDateTime, isTerminal } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import {
  artifactLabel,
  artifactOf,
  ChunkDecoder,
  fileStatusLabel,
  languageOf,
  logsOf,
  MAX_CHUNK,
  type FileChange,
  type GitChanges,
} from './artifacts'
import { CodeView } from './CodeView'

/** Lo que se lee de un log de una vez. */
export const LOG_PAGE = 512 * 1024

/** Artefactos del agente: lo que cambió en git, su diff y los logs (ADR-0001 §13.5). */
export function ArtifactsTab({ agent }: { agent: AgentRun }) {
  const artifacts = useQuery({
    queryKey: ['artifacts', agent.id],
    queryFn: () => api.artifacts(agent.id),
  })
  if (artifacts.error) return <ErrorMessage error={artifacts.error} />
  if (!artifacts.data) return <p className="muted">Cargando…</p>
  if (artifacts.data.length === 0) {
    return (
      <p className="muted">
        {isTerminal(agent.status)
          ? 'Esta invocación no tiene artefactos.'
          : 'Los artefactos llegan cuando termina la invocación.'}
      </p>
    )
  }
  const changes = artifactOf(artifacts.data, 'GIT_CHANGES')
  const diff = artifactOf(artifacts.data, 'DIFF')
  return (
    <>
      {changes ? (
        <Changes changes={changes} diff={diff} />
      ) : (
        <p className="muted">Sin datos de git para esta invocación.</p>
      )}
      <Logs artifacts={logsOf(artifacts.data)} />
    </>
  )
}

/** Contenido completo de un artefacto pequeño (hasta 8 MiB) como texto. */
function useArtifactText(artifact: ArtifactSummary | undefined, offset = 0, length?: number) {
  const limit = Math.min(length ?? artifact?.size ?? 0, MAX_CHUNK)
  return useQuery({
    queryKey: ['artifact-content', artifact?.id, offset, limit],
    queryFn: async () => {
      const chunk = await api.artifactChunk(artifact!.id, offset, limit)
      return new TextDecoder().decode(chunk.bytes)
    },
    enabled: artifact != null,
    // El contenido de un artefacto no cambia nunca.
    staleTime: Infinity,
  })
}

function Changes({ changes, diff }: { changes: ArtifactSummary; diff?: ArtifactSummary }) {
  const content = useArtifactText(changes)
  const [selected, setSelected] = useState<string | null>(null)
  if (content.error) return <ErrorMessage error={content.error} />
  if (content.data == null) return <p className="muted">Cargando cambios…</p>
  let git: GitChanges
  try {
    git = JSON.parse(content.data) as GitChanges
  } catch {
    return <p className="error small">No se pudieron leer los cambios (JSON recortado o roto).</p>
  }
  const file = git.files.find((f) => f.path === selected) ?? git.files[0]

  return (
    <>
      <h3>Cambios</h3>
      <p className="small">
        Rama <code>{git.branch}</code> · <code>{git.baseCommit.slice(0, 8)}</code> →{' '}
        <code>{git.headCommit.slice(0, 8)}</code>
        {git.uncommittedFiles > 0 && (
          <span className="attention"> · {git.uncommittedFiles} archivos sin confirmar</span>
        )}
      </p>
      {git.files.length === 0 ? (
        <p className="muted">La invocación no cambió ningún archivo.</p>
      ) : (
        <div className="changes">
          <ul className="changed-files" aria-label="Archivos modificados">
            {git.files.map((f) => (
              <li key={f.path}>
                <button
                  type="button"
                  className={f === file ? 'link selected' : 'link'}
                  aria-current={f === file ? 'true' : undefined}
                  onClick={() => setSelected(f.path)}
                >
                  <span className="file-status" title={fileStatusLabel(f.status)}>
                    {f.status}
                  </span>{' '}
                  {f.oldPath ? `${f.oldPath} → ${f.path}` : f.path}
                </button>{' '}
                {f.binary ? (
                  <span className="muted small">binario</span>
                ) : (
                  <span className="small">
                    <span className="inserted">+{f.insertions}</span>{' '}
                    <span className="deleted">−{f.deletions}</span>
                  </span>
                )}
              </li>
            ))}
          </ul>
          {file && <FileDiff file={file} diff={diff} />}
        </div>
      )}
      <h3>Commits</h3>
      {git.commits.length === 0 ? (
        <p className="muted small">Sin commits nuevos.</p>
      ) : (
        <ol className="commits small">
          {git.commits.map((c) => (
            <li key={c.sha}>
              <code>{c.sha.slice(0, 8)}</code> {c.subject}{' '}
              <span className="muted">
                · {c.author} · {formatDateTime(c.date)}
              </span>
            </li>
          ))}
        </ol>
      )}
    </>
  )
}

function FileDiff({ file, diff }: { file: FileChange; diff?: ArtifactSummary }) {
  const ranged = file.diffOffset != null && file.diffLength != null
  const content = useArtifactText(
    diff && ranged ? diff : undefined,
    file.diffOffset ?? 0,
    file.diffLength,
  )
  if (!diff) return <p className="muted small">El diff no llegó.</p>
  if (!ranged) return <p className="muted small">No se pudo situar el diff de este archivo.</p>
  if (content.error) return <ErrorMessage error={content.error} />
  if (content.data == null) return <p className="muted small">Cargando diff…</p>
  const cut = (file.diffLength ?? 0) > MAX_CHUNK
  const beyondTruncation =
    diff.truncated && (file.diffOffset ?? 0) + (file.diffLength ?? 0) > diff.size
  return (
    <div className="file-diff">
      <CodeView text={content.data} language="diff" label={`Diff de ${file.path}`} />
      {(cut || beyondTruncation) && (
        <p className="muted small">El diff de este archivo está recortado.</p>
      )}
    </div>
  )
}

function Logs({ artifacts }: { artifacts: ArtifactSummary[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (artifacts.length === 0) return null
  return (
    <>
      <h3>Logs</h3>
      <ul className="list small">
        {artifacts.map((a) => (
          <li key={a.id}>
            <button
              type="button"
              className="link"
              aria-expanded={open === a.id}
              onClick={() => setOpen(open === a.id ? null : a.id)}
            >
              {artifactLabel(a)}
            </button>{' '}
            <span className="muted">
              {a.name} · {formatBytes(a.size)} · {formatDateTime(a.createdAt)}
              {a.truncated && ' · recortado'}
            </span>
            {open === a.id && <LogViewer artifact={a} />}
          </li>
        ))}
      </ul>
    </>
  )
}

/** Log leído por páginas: la primera al abrirlo y las siguientes con «Cargar más». */
function LogViewer({ artifact }: { artifact: ArtifactSummary }) {
  const [text, setText] = useState('')
  const [loaded, setLoaded] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const decoder = useRef(new ChunkDecoder())
  const started = useRef(false)

  async function more(offset: number) {
    setLoading(true)
    setError(null)
    try {
      const chunk = await api.artifactChunk(artifact.id, offset, LOG_PAGE)
      const end = offset + chunk.bytes.length
      const piece = decoder.current.push(chunk.bytes, end >= chunk.size)
      setText((t) => t + piece)
      setLoaded(end)
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (started.current) return
    started.current = true
    void more(0)
    // Solo la primera página, al montar.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="log-viewer">
      <ErrorMessage error={error} />
      {loaded > 0 && (
        <CodeView
          text={text}
          language={languageOf(artifact.mediaType)}
          label={artifactLabel(artifact)}
          height={320}
        />
      )}
      <p className="muted small">
        {loading ? 'Cargando…' : `${formatBytes(loaded)} de ${formatBytes(artifact.size)}`}{' '}
        {!loading && loaded < artifact.size && (
          <button type="button" className="link" onClick={() => void more(loaded)}>
            Cargar más
          </button>
        )}
      </p>
    </div>
  )
}
