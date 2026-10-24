import { useQuery } from '@tanstack/react-query'
import { Brain, Pin, ShieldAlert } from 'lucide-react'
import { useState } from 'react'
import {
  api,
  MEMORY_KINDS,
  type MemoryKind,
  type MemoryObservation,
  type Repository,
} from '../../api'
import { formatDateTime, truncate } from '../../format'
import { ArchivedToggle } from '../archive/Archive'
import { ErrorMessage } from '../ErrorMessage'
import { EmptyState } from '../list/DataTable'
import { Pager } from '../list/Pager'
import { ListToolbar, SearchField } from '../list/Toolbar'
import { CardsSkeleton } from '../ui/Skeleton'
import { Sheet } from '../ui/Sheet'
import { MemoryDetail } from './MemoryDetail'
import { dayLabel, kindLabel, ORIGIN_LABEL } from './memoryText'

const PAGE_SIZE = 50

/**
 * La memoria del proyecto (ME-D): lo que guardan los agentes, las personas y la bitácora de Skynet,
 * como una línea de tiempo que se puede buscar y filtrar por repositorio, tipo y fijadas. Cada
 * entrada se abre en un panel lateral con su procedencia y sus versiones, y ahí se edita, se fija
 * o se archiva. La entrada abierta va en la URL para poder enlazarla desde una invocación.
 */
export function ProjectMemory({
  projectId,
  repositories,
  editable,
  archived,
  onArchived,
  entryId,
  onEntry,
}: {
  projectId: string
  repositories: Repository[]
  /** El proyecto no está archivado. */
  editable: boolean
  /** Enseña solo las archivadas. */
  archived: boolean
  onArchived: (on: boolean) => void
  entryId: string | null
  onEntry: (id: string | null) => void
}) {
  const [q, setQ] = useState('')
  const [repository, setRepository] = useState('')
  const [kind, setKind] = useState<MemoryKind | ''>('')
  const [pinned, setPinned] = useState(false)
  const [page, setPage] = useState(0)
  const filters = {
    q,
    repository: repository || undefined,
    kind: kind || undefined,
    pinned: pinned || undefined,
    archived: archived ? ('true' as const) : undefined,
    page,
    size: PAGE_SIZE,
  }
  const memory = useQuery({
    queryKey: ['memory', projectId, filters],
    queryFn: () => api.memory(projectId, filters),
  })
  const repoName = (id: string | null) =>
    id == null
      ? 'Todo el proyecto'
      : (repositories.find((r) => r.id === id)?.name ?? 'Otro repositorio')
  const filter =
    <T,>(set: (v: T) => void) =>
    (v: T) => {
      set(v)
      setPage(0)
    }
  const items = memory.data?.items ?? []
  // Sin texto la lista va por fecha; con texto, por pertinencia, así que no se agrupa por días.
  const groups = q ? [{ label: null, items }] : byDay(items)
  const filtered = !!(q || repository || kind || pinned)

  return (
    <>
      <ListToolbar>
        <SearchField
          value={q}
          onChange={filter(setQ)}
          placeholder="Buscar en la memoria"
          label="Buscar en la memoria"
        />
        <label className="inline-field">
          <span className="visually-hidden">Repositorio</span>
          <select value={repository} onChange={(e) => filter(setRepository)(e.target.value)}>
            <option value="">Todo</option>
            <option value="project">Solo lo de todo el proyecto</option>
            {repositories.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label className="inline-field">
          <span className="visually-hidden">Tipo</span>
          <select value={kind} onChange={(e) => filter(setKind)(e.target.value as MemoryKind | '')}>
            <option value="">Todos los tipos</option>
            {MEMORY_KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className={pinned ? 'chip chip-on' : 'chip'}
          aria-pressed={pinned}
          onClick={() => filter(setPinned)(!pinned)}
        >
          <Pin size={14} strokeWidth={2.5} aria-hidden="true" />
          Fijadas
        </button>
        <ArchivedToggle
          on={archived}
          onChange={(on) => {
            setPage(0)
            onArchived(on)
          }}
          label="Archivadas"
        />
      </ListToolbar>

      <ErrorMessage error={memory.error} />
      {memory.isPending && <CardsSkeleton label="la memoria" />}
      {memory.data?.total === 0 &&
        (filtered || archived ? (
          <EmptyState icon={Brain} title="Nada coincide con el filtro." />
        ) : (
          <EmptyState icon={Brain} title="La memoria del proyecto está vacía.">
            <p>
              Los agentes guardan aquí decisiones, descubrimientos y trampas mientras trabajan, y
              Skynet deja la bitácora de cada ejecución al terminar.
            </p>
          </EmptyState>
        ))}
      {items.length > 0 && (
        <ol className="memory-timeline" aria-label="Memoria del proyecto">
          {groups.map((g) => (
            <li key={g.label ?? 'results'}>
              {g.label && <h3 className="memory-day">{g.label}</h3>}
              <ul className="memory-list">
                {g.items.map((o) => (
                  <li key={o.id}>
                    <MemoryCard
                      observation={o}
                      scope={repoName(o.repositoryId)}
                      onOpen={() => onEntry(o.id)}
                    />
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      )}
      {memory.data && (
        <Pager
          page={page}
          pageSize={PAGE_SIZE}
          total={memory.data.total}
          noun="entradas"
          onPage={setPage}
        />
      )}

      <Sheet
        open={entryId != null}
        onOpenChange={(o) => !o && onEntry(null)}
        title="Entrada de la memoria"
      >
        {entryId && (
          <MemoryDetail id={entryId} projectId={projectId} scope={repoName} editable={editable} />
        )}
      </Sheet>
    </>
  )
}

function MemoryCard({
  observation: o,
  scope,
  onOpen,
}: {
  observation: MemoryObservation
  scope: string
  onOpen: () => void
}) {
  return (
    <button type="button" className="memory-item" onClick={onOpen}>
      <span className="memory-item-head">
        <span className={o.kind === 'RUN_LOG' ? 'tag tag-on' : 'tag'}>{kindLabel(o.kind)}</span>
        <strong>{o.title}</strong>
        {o.pinned && <Pin size={16} strokeWidth={2.5} aria-label="Fijada" />}
        {o.redacted && <ShieldAlert size={16} strokeWidth={2.5} aria-label="Secreto enmascarado" />}
      </span>
      <span className="memory-item-what">{truncate(o.what, 180)}</span>
      <small className="muted">
        {scope} · v{o.version} · de {ORIGIN_LABEL[o.origin]} · {formatDateTime(o.updatedAt)}
        {o.archivedAt && ' · archivada'}
      </small>
    </button>
  )
}

/**
 * Las fijadas primero (el servidor ya las pone delante) y el resto agrupado por el día de su último
 * cambio, en el orden en que llegan.
 */
function byDay(items: MemoryObservation[]) {
  const groups: { label: string | null; items: MemoryObservation[] }[] = []
  const pinned = items.filter((o) => o.pinned)
  if (pinned.length > 0) groups.push({ label: 'Fijadas', items: pinned })
  for (const o of items.filter((o) => !o.pinned)) {
    const label = dayLabel(o.updatedAt)
    const last = groups.at(-1)
    if (last?.label === label) last.items.push(o)
    else groups.push({ label, items: [o] })
  }
  return groups
}
