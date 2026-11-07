import type { CatalogEntry, DiscoveredRepository, ImportProjectRequest } from '../../api'

/** Lo que ve la página del proyecto al llegar desde el asistente. */
export interface ImportedState {
  importedFrom: string
  learn?: 'launched' | 'missing' | 'failed'
  learnRunId?: string
}

/** Lo que se edita en el asistente «Importar proyecto» (IP-C). */
export interface ImportForm {
  key: string
  name: string
  description: string
  repoName: string
  remoteUrl: string
  defaultBranch: string
  validationCommand: string
  testReportPaths: string
  protectedPaths: string
}

const TOOLS: Record<string, string> = {
  gradle: 'Gradle',
  maven: 'Maven',
  npm: 'npm',
  pnpm: 'pnpm',
  yarn: 'Yarn',
  python: 'Python',
  cargo: 'Rust',
  go: 'Go',
}

export function toolLabel(tool: string | null): string | null {
  return tool ? (TOOLS[tool] ?? tool) : null
}

/** «forma» → «Forma», «forma-app» → «Forma app». */
export function displayName(name: string): string {
  const spaced = name.replace(/[-_]+/g, ' ').trim()
  return spaced ? spaced.charAt(0).toUpperCase() + spaced.slice(1) : name
}

/** `/home/diego/code/forma` → `~/code/forma` si se conoce la carpeta personal. */
export function shortPath(path: string): string {
  return path.replace(/^\/(home|Users)\/[^/]+(?=\/|$)/, '~')
}

/** `git@github.com:guilu/forma.git` → `github.com/guilu/forma`. */
export function shortRemote(url: string): string {
  return url
    .replace(/^[a-z+]+:\/\/(?:[^@/]+@)?/i, '')
    .replace(/^[^@/]+@([^:/]+):/, '$1/')
    .replace(/\.git$/, '')
}

/** La descripción que se propone: la del repositorio y, si no tiene, la de GitHub. */
export function proposedDescription(entry: CatalogEntry): { text: string; source: string | null } {
  const r = entry.repository
  if (r.description) return { text: r.description, source: r.descriptionSource }
  if (entry.github?.description) return { text: entry.github.description, source: 'GitHub' }
  return { text: '', source: null }
}

/** Formulario inicial a partir de lo que dedujo el runner; la clave llega aparte. */
export function initialForm(entry: CatalogEntry, key = ''): ImportForm {
  const r = entry.repository
  return {
    key,
    name: displayName(r.projectName),
    description: proposedDescription(entry).text,
    repoName: r.name,
    remoteUrl: r.remoteUrl ?? '',
    defaultBranch: r.defaultBranch,
    validationCommand: r.validationCommand ?? '',
    testReportPaths: r.testReportPaths.join('\n'),
    protectedPaths: r.protectedPaths.join('\n'),
  }
}

const lines = (text: string) =>
  text
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)

export function importRequest(path: string, form: ImportForm): ImportProjectRequest {
  const opt = (v: string) => (v.trim() ? v.trim() : undefined)
  return {
    key: form.key.trim(),
    name: form.name.trim(),
    description: opt(form.description),
    repository: {
      name: form.repoName.trim(),
      localPath: path,
      remoteUrl: opt(form.remoteUrl),
      defaultBranch: opt(form.defaultBranch),
      validationCommand: opt(form.validationCommand),
      testReportPaths: lines(form.testReportPaths),
      protectedPaths: lines(form.protectedPaths),
    },
  }
}

export const KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/

/** De dónde sale el nombre del proyecto. */
export function nameSource(r: DiscoveredRepository): string {
  return r.projectNameSource === 'directory'
    ? 'Del nombre de la carpeta'
    : `De ${r.projectNameSource}`
}

export function branchSource(r: DiscoveredRepository): string {
  switch (r.defaultBranchSource) {
    case 'origin/HEAD':
      return 'De origin/HEAD'
    case 'current':
      return 'La rama actual: no hay origin/HEAD'
    default:
      return 'Por defecto: el repositorio aún no tiene ramas'
  }
}

export function validationSource(r: DiscoveredRepository): string {
  const tool = toolLabel(r.tool)
  if (!tool) return 'No se reconoce la herramienta: sin comando no se verifica'
  if (!r.validationCommand) return `${tool} sin script de tests: sin comando no se verifica`
  return `Sugerido para ${tool}`
}

export function remoteSource(entry: CatalogEntry): { text: string; ok: boolean } {
  if (!entry.repository.remoteUrl) return { text: 'Sin remoto: no habrá PR ni GitHub', ok: false }
  if (entry.github?.covered) return { text: 'De git remote · la App de GitHub lo cubre', ok: true }
  if (entry.github) return { text: 'De git remote · la App de GitHub no lo cubre', ok: false }
  return { text: 'De git remote', ok: false }
}

/** Entradas que casan con la búsqueda, por nombre, ruta o remoto. */
export function matchesSearch(entry: CatalogEntry, q: string): boolean {
  const text = q.trim().toLowerCase()
  if (!text) return true
  const r = entry.repository
  return [r.name, r.projectName, r.path, r.remoteUrl ?? ''].join(' ').toLowerCase().includes(text)
}
