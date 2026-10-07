import type { ArtifactSummary, ArtifactType } from '../../api'

/** Lo más que se pide de una vez (el servidor sirve 8 MiB como mucho). */
export const MAX_CHUNK = 8 * 1024 * 1024

/** Un commit nuevo de la invocación. */
export interface Commit {
  sha: string
  author: string
  email: string
  date: string
  subject: string
}

/** Un archivo cambiado, con la posición de su trozo dentro del artefacto `DIFF`. */
export interface FileChange {
  path: string
  oldPath?: string
  /** Letra de git: `A`, `M`, `D`, `R`, `C`, `T`. */
  status: string
  insertions: number
  deletions: number
  binary: boolean
  diffOffset?: number
  diffLength?: number
}

/** Contenido del artefacto `GIT_CHANGES`. */
export interface GitChanges {
  branch: string
  baseCommit: string
  headCommit: string
  commits: Commit[]
  files: FileChange[]
  uncommittedFiles: number
}

/** Caso fallido del artefacto `TEST_REPORT`. */
export interface TestFailure {
  suite: string
  className: string
  name: string
  kind: 'failure' | 'error'
  type: string
  message: string
  details: string
}

/** Contenido del artefacto `TEST_REPORT`. */
export interface TestReport {
  total: number
  failed: number
  errors: number
  skipped: number
  reports: string[]
  unreadable: string[]
  failures: TestFailure[]
}

const STATUS_LABELS: Record<string, string> = {
  A: 'Añadido',
  M: 'Modificado',
  D: 'Borrado',
  R: 'Renombrado',
  C: 'Copiado',
  T: 'Cambio de tipo',
}

export const fileStatusLabel = (status: string) => STATUS_LABELS[status] ?? status

/** Artefacto más reciente de un tipo, de la invocación o de una verificación concreta. */
export function artifactOf(
  artifacts: ArtifactSummary[],
  type: ArtifactType,
  verificationRunId: string | null = null,
): ArtifactSummary | undefined {
  return artifacts
    .filter((a) => a.type === type && a.verificationRunId === verificationRunId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
}

const LOG_TYPES: ArtifactType[] = ['LOG', 'VERIFICATION_LOG', 'RESULT', 'PROMPT', 'TEST_REPORT']

const LOG_LABELS: Partial<Record<ArtifactType, string>> = {
  LOG: 'Salida del agente (NDJSON)',
  VERIFICATION_LOG: 'Salida de la verificación',
  RESULT: 'Resultado final',
  PROMPT: 'Prompt',
  TEST_REPORT: 'Informe de tests',
}

export const artifactLabel = (a: ArtifactSummary) => LOG_LABELS[a.type] ?? a.name

/** Artefactos que se leen como texto, en el orden en que se muestran. */
export function logsOf(artifacts: ArtifactSummary[]): ArtifactSummary[] {
  return artifacts
    .filter((a) => LOG_TYPES.includes(a.type))
    .sort(
      (a, b) =>
        LOG_TYPES.indexOf(a.type) - LOG_TYPES.indexOf(b.type) ||
        b.createdAt.localeCompare(a.createdAt),
    )
}

/** Lenguaje del visor para un artefacto de texto. */
export function languageOf(mediaType: string): 'json' | 'plaintext' {
  const base = mediaType.split(';')[0].trim()
  return base === 'application/json' ? 'json' : 'plaintext'
}

/**
 * Decodifica trozos UTF-8 consecutivos. Un trozo puede cortar un carácter por la mitad: el
 * decodificador en modo `stream` guarda los bytes sueltos para el siguiente.
 */
export class ChunkDecoder {
  private readonly decoder = new TextDecoder()

  push(bytes: Uint8Array, last: boolean): string {
    return this.decoder.decode(bytes, { stream: !last })
  }
}
