import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpen, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { api, type WikiRevision, type WikiSource, type WikiSummary } from '../../api'
import { formatDateTime } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { EmptyState } from '../list/DataTable'
import { ListToolbar } from '../list/Toolbar'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'
import { CardsSkeleton, LinesSkeleton } from '../ui/Skeleton'
import { SOURCE_KIND_LABEL, WIKI_ORIGIN_LABEL } from './memoryText'
import { WikiMarkdown } from './WikiMarkdown'

const INDEX = 'indice'

/** Una página: su nombre y su repositorio (`null`, la de todo el proyecto). */
interface PageRef {
  slug: string
  repositoryId: string | null
}

/**
 * La wiki del proyecto (MV-C, MV-E): las páginas que genera Skynet al terminar cada ejecución, las
 * que escribe el curador y las de las personas, agrupadas por ámbito. Cada página se abre en un
 * panel lateral con sus fuentes y sus versiones; sus enlaces a otras páginas se siguen ahí mismo.
 */
export function ProjectWiki({
  projectId,
  scope,
  editable,
  onEntry,
}: {
  projectId: string
  scope: (repositoryId: string | null) => string
  editable: boolean
  /** Abre una entrada de la memoria (una fuente de la página). */
  onEntry: (id: string) => void
}) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState<PageRef | null>(null)
  const wiki = useQuery({ queryKey: ['wiki', projectId], queryFn: () => api.wiki(projectId) })
  const refresh = useMutation({
    mutationFn: () => api.refreshWiki(projectId),
    onSuccess: async (pages) => {
      queryClient.setQueryData(['wiki', projectId], pages)
      await queryClient.invalidateQueries({ queryKey: ['wiki-page', projectId] })
    },
  })
  const pages = wiki.data ?? []
  // Un enlace de una página lleva solo el nombre: primero la del mismo ámbito y si no, cualquiera.
  const follow = (from: string | null) => (slug: string) => {
    const target =
      pages.find((p) => p.slug === slug && p.repositoryId === from) ??
      pages.find((p) => p.slug === slug)
    setOpen({ slug, repositoryId: target?.repositoryId ?? from })
  }

  return (
    <>
      {editable && (
        <ListToolbar>
          <Button
            size="sm"
            variant="secondary"
            disabled={refresh.isPending}
            onClick={() => refresh.mutate()}
          >
            <RefreshCw size={16} strokeWidth={2.5} aria-hidden="true" />
            {refresh.isPending ? 'Regenerando…' : 'Regenerar las páginas de Skynet'}
          </Button>
        </ListToolbar>
      )}
      <ErrorMessage error={refresh.error} />
      <ErrorMessage error={wiki.error} />
      {wiki.isPending && <CardsSkeleton label="la wiki" />}
      {wiki.data?.length === 0 && (
        <EmptyState icon={BookOpen} title="La wiki del proyecto está vacía.">
          <p>
            Skynet escribe sus páginas al terminar cada ejecución, y el curador de la memoria añade
            las suyas. Si el proyecto ya tenía historia, regenera las páginas.
          </p>
        </EmptyState>
      )}
      {pages.length > 0 && (
        <div className="wiki-groups">
          {byScope(pages).map((g) => (
            <section key={g.repositoryId ?? 'project'} aria-label={scope(g.repositoryId)}>
              <h3 className="memory-day">{scope(g.repositoryId)}</h3>
              <ul className="memory-list">
                {g.pages.map((p) => (
                  <li key={p.id}>
                    <WikiCard
                      page={p}
                      onOpen={() => setOpen({ slug: p.slug, repositoryId: p.repositoryId })}
                    />
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}

      <Sheet
        open={open != null}
        onOpenChange={(o) => !o && setOpen(null)}
        title="Página de la wiki"
      >
        {open && (
          <WikiPageView
            key={`${open.repositoryId}/${open.slug}`}
            projectId={projectId}
            page={open}
            scope={scope}
            onPage={follow(open.repositoryId)}
            onEntry={(id) => {
              setOpen(null)
              onEntry(id)
            }}
          />
        )}
      </Sheet>
    </>
  )
}

function WikiCard({ page: p, onOpen }: { page: WikiSummary; onOpen: () => void }) {
  return (
    <button type="button" className="memory-item" onClick={onOpen}>
      <span className="memory-item-head">
        <strong>{p.title}</strong>
        <span className={p.origin === 'GENERATED' ? 'tag' : 'tag tag-on'}>
          {WIKI_ORIGIN_LABEL[p.origin]}
        </span>
      </span>
      <small className="muted">
        <code>{p.slug}</code> · v{p.version} · {sourcesCount(p.sources)} ·{' '}
        {formatDateTime(p.updatedAt)}
      </small>
    </button>
  )
}

function WikiPageView({
  projectId,
  page: ref,
  scope,
  onPage,
  onEntry,
}: {
  projectId: string
  page: PageRef
  scope: (repositoryId: string | null) => string
  onPage: (slug: string) => void
  onEntry: (id: string) => void
}) {
  const page = useQuery({
    queryKey: ['wiki-page', projectId, ref.repositoryId, ref.slug],
    queryFn: () => api.wikiPage(projectId, ref.slug, ref.repositoryId),
  })
  const revisions = useQuery({
    queryKey: ['wiki-page', projectId, ref.repositoryId, ref.slug, 'revisions'],
    queryFn: () => api.wikiRevisions(projectId, ref.slug, ref.repositoryId),
  })

  if (page.error) return <ErrorMessage error={page.error} />
  if (!page.data) return <LinesSkeleton label="la página" />
  const p = page.data

  return (
    <article className="memory-detail" aria-labelledby="wiki-title">
      <p className="memory-tags">
        <span className="tag">{scope(p.repositoryId)}</span>
        <span className="tag">v{p.version}</span>
        <span className={p.origin === 'GENERATED' ? 'tag' : 'tag tag-on'}>
          {WIKI_ORIGIN_LABEL[p.origin]}
        </span>
      </p>
      <h2 id="wiki-title">{p.title}</h2>
      <p className="small muted">
        <code>{p.slug}</code> · cambiada el {formatDateTime(p.updatedAt)}
        {p.updatedBy && ` por ${p.updatedBy}`}
        {p.origin === 'GENERATED' && '. La regenera Skynet: no se edita a mano.'}
      </p>

      <WikiMarkdown text={p.body} label={`Contenido de ${p.title}`} onPage={onPage} />

      <h3>Fuentes</h3>
      <Sources sources={p.sources} onEntry={onEntry} />

      <h3>Versiones</h3>
      <ErrorMessage error={revisions.error} />
      {revisions.data && <Revisions revisions={revisions.data} onPage={onPage} />}
    </article>
  )
}

/** De dónde sale lo que dice la página: las entradas se abren aquí; las PRs, en GitHub. */
function Sources({ sources, onEntry }: { sources: WikiSource[]; onEntry: (id: string) => void }) {
  if (sources.length === 0) {
    return <p className="small muted">La página no cita fuentes.</p>
  }
  return (
    <ul className="memory-readers memory-list small" aria-label="Fuentes">
      {sources.map((s) => (
        <li key={`${s.kind}-${s.id}`}>
          <span className="tag">{SOURCE_KIND_LABEL[s.kind] ?? s.kind}</span>{' '}
          <SourceLabel source={s} onEntry={onEntry} />
        </li>
      ))}
    </ul>
  )
}

function SourceLabel({
  source: s,
  onEntry,
}: {
  source: WikiSource
  onEntry: (id: string) => void
}) {
  if (s.kind === 'memory') {
    return (
      <Button variant="link" onClick={() => onEntry(s.id)}>
        {s.label}
      </Button>
    )
  }
  if (s.url) {
    return (
      <a href={s.url} target="_blank" rel="noopener noreferrer">
        {s.label}
      </a>
    )
  }
  return <>{s.label}</>
}

function Revisions({
  revisions,
  onPage,
}: {
  revisions: WikiRevision[]
  onPage: (slug: string) => void
}) {
  return (
    <ol className="memory-revisions">
      {revisions.map((r) => (
        <li key={r.version}>
          <details>
            <summary>
              <strong>v{r.version}</strong> · {r.title} ·{' '}
              <span className="muted">
                {WIKI_ORIGIN_LABEL[r.origin]}
                {r.createdBy && ` (${r.createdBy})`}, {formatDateTime(r.createdAt)} ·{' '}
                {sourcesCount(r.sources.length)}
              </span>
            </summary>
            <WikiMarkdown text={r.body} label={`Versión ${r.version}`} onPage={onPage} />
          </details>
        </li>
      ))}
    </ol>
  )
}

const sourcesCount = (n: number) => (n === 1 ? '1 fuente' : `${n} fuentes`)

/** Primero las de todo el proyecto, con el índice delante; después, cada repositorio. */
function byScope(pages: WikiSummary[]) {
  const groups: { repositoryId: string | null; pages: WikiSummary[] }[] = []
  const sorted = [...pages].sort(
    (a, b) =>
      Number(a.repositoryId != null) - Number(b.repositoryId != null) ||
      Number(b.slug === INDEX) - Number(a.slug === INDEX),
  )
  for (const p of sorted) {
    const last = groups.at(-1)
    if (last?.repositoryId === p.repositoryId) last.pages.push(p)
    else groups.push({ repositoryId: p.repositoryId, pages: [p] })
  }
  return groups
}
