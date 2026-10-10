import * as Menu from '@radix-ui/react-dropdown-menu'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, Ellipsis, Trash2 } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { api, type ArchiveTarget } from '../../api'
import { formatDateTime } from '../../format'
import { cn } from '../../lib/cn'
import { ErrorMessage } from '../ErrorMessage'
import { Button } from '../ui/Button'
import { Sheet } from '../ui/Sheet'
import { describeCounts } from './counts'

/** Textos de cada cosa: los runners no se archivan, se «olvidan». */
const WORDS: Record<ArchiveTarget['kind'], { archive: string; restore: string; noun: string }> = {
  project: { archive: 'Archivar', restore: 'Restaurar', noun: 'el proyecto' },
  repository: { archive: 'Archivar', restore: 'Restaurar', noun: 'el repositorio' },
  'work-item': { archive: 'Archivar', restore: 'Restaurar', noun: 'el trabajo' },
  run: { archive: 'Archivar', restore: 'Restaurar', noun: 'la ejecución' },
  runner: { archive: 'Olvidar', restore: 'Volver a enseñar', noun: 'el runner' },
  workflow: { archive: 'Archivar', restore: 'Restaurar', noun: 'el workflow' },
}

/** Tras archivar, restaurar o eliminar cambia casi todo (listas, dashboard, métricas): se relee. */
function useRefreshAll() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries()
}

/**
 * Menú «⋯» de una página o una fila: archivar (u olvidar) y, sobre lo archivado, restaurar y
 * eliminar. Archivar y restaurar son reversibles y se hacen al momento; eliminar abre un panel con
 * lo que se borraría.
 */
export function ArchiveMenu({
  target,
  name,
  archivedAt,
  onDeleted,
  size = 'md',
}: {
  target: ArchiveTarget
  /** Nombre visible («TKM · Tokens»), para la etiqueta del menú y el panel. */
  name: string
  archivedAt: string | null
  /** Tras eliminar: p. ej. volver a la lista. */
  onDeleted?: () => void
  size?: 'sm' | 'md'
}) {
  const words = WORDS[target.kind]
  const refresh = useRefreshAll()
  const [deleting, setDeleting] = useState(false)
  const change = useMutation({
    mutationFn: (action: 'archive' | 'restore') =>
      action === 'archive' ? api.archive(target) : api.restore(target),
    onSuccess: () => refresh(),
  })

  return (
    <span className="archive-menu">
      <Menu.Root modal={false}>
        <Menu.Trigger
          className={cn('icon-btn', size === 'sm' && 'icon-btn-sm')}
          aria-label={`Más acciones: ${name}`}
        >
          <Ellipsis size={size === 'sm' ? 18 : 22} strokeWidth={2.5} aria-hidden="true" />
        </Menu.Trigger>
        <Menu.Portal>
          <Menu.Content className="menu" align="end" sideOffset={8}>
            {archivedAt == null ? (
              <Menu.Item
                className="menu-item"
                disabled={change.isPending}
                onSelect={() => change.mutate('archive')}
              >
                <Archive size={18} strokeWidth={2.25} aria-hidden="true" />
                {words.archive}
              </Menu.Item>
            ) : (
              <>
                <Menu.Item
                  className="menu-item"
                  disabled={change.isPending}
                  onSelect={() => change.mutate('restore')}
                >
                  <ArchiveRestore size={18} strokeWidth={2.25} aria-hidden="true" />
                  {words.restore}
                </Menu.Item>
                {/* Las versiones publicadas de un workflow no se borran: solo se archiva. */}
                {target.kind !== 'workflow' && (
                  <Menu.Item className="menu-item menu-danger" onSelect={() => setDeleting(true)}>
                    <Trash2 size={18} strokeWidth={2.25} aria-hidden="true" />
                    Eliminar…
                  </Menu.Item>
                )}
              </>
            )}
          </Menu.Content>
        </Menu.Portal>
      </Menu.Root>
      {change.error && (
        <span className="archive-error">
          <ErrorMessage error={change.error} />
        </span>
      )}
      <Sheet
        open={deleting}
        onOpenChange={setDeleting}
        title={`Eliminar ${words.noun}`}
        description={name}
      >
        {deleting && (
          <DeletePanel
            target={target}
            onCancel={() => setDeleting(false)}
            onDeleted={() => {
              setDeleting(false)
              onDeleted?.()
              void refresh()
            }}
          />
        )}
      </Sheet>
    </span>
  )
}

/**
 * Lo que se borraría, lo que lo impide y la confirmación. Mientras queden worktrees vivos, ofrece
 * eliminarlos antes y vuelve a mirar cada pocos segundos hasta que el runner termina.
 */
function DeletePanel({
  target,
  onCancel,
  onDeleted,
}: {
  target: ArchiveTarget
  onCancel: () => void
  onDeleted: () => void
}) {
  const preview = useQuery({
    queryKey: ['deletion-preview', target.kind, target.id],
    queryFn: () => api.deletionPreview(target),
    refetchInterval: (query) => ((query.state.data?.liveWorkspaces ?? 0) > 0 ? 3000 : false),
  })
  const cleanup = useMutation({
    mutationFn: () => api.cleanupWorkspaces(target),
    onSuccess: () => void preview.refetch(),
  })
  const remove = useMutation({ mutationFn: () => api.delete(target), onSuccess: onDeleted })
  const data = preview.data
  const canCleanup = target.kind !== 'repository' && target.kind !== 'runner'

  return (
    <div className="delete-panel">
      <ErrorMessage error={preview.error} />
      {preview.isPending && <p className="muted">Calculando lo que se borraría…</p>}
      {data && (
        <>
          <p>
            <strong>Se borrará:</strong> {describeCounts(data.counts)}.
          </p>
          <p className="muted small">
            No tiene vuelta atrás. En Actividad quedará una nota con lo que se borró y cuándo.
          </p>
          {data.warnings.length > 0 && (
            <ul className="delete-warnings">
              {data.warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
          {data.blockers.length > 0 && (
            <div className="delete-blockers" role="alert">
              <p>
                <strong>Ahora no se puede eliminar:</strong>
              </p>
              <ul>
                {data.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
              {data.liveWorkspaces > 0 && canCleanup && (
                <Button
                  variant="secondary"
                  onClick={() => cleanup.mutate()}
                  disabled={cleanup.isPending || cleanup.isSuccess}
                >
                  {cleanup.isSuccess
                    ? 'Eliminando los worktrees…'
                    : data.liveWorkspaces === 1
                      ? 'Eliminar el worktree'
                      : `Eliminar los ${data.liveWorkspaces} worktrees`}
                </Button>
              )}
              <ErrorMessage error={cleanup.error} />
            </div>
          )}
        </>
      )}
      <div className="form-actions">
        <Button
          variant="danger"
          onClick={() => remove.mutate()}
          disabled={!data?.deletable || remove.isPending}
        >
          <Trash2 size={18} strokeWidth={2.5} aria-hidden="true" />
          {remove.isPending ? 'Eliminando…' : 'Eliminar definitivamente'}
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          Cancelar
        </Button>
      </div>
      <ErrorMessage error={remove.error} />
    </div>
  )
}

/** Aviso en la página de algo archivado: desde cuándo, que es de solo lectura y «Restaurar». */
export function ArchivedBanner({
  target,
  archivedAt,
  note,
}: {
  target: ArchiveTarget
  archivedAt: string
  /** Lo que no se puede hacer mientras siga archivado. */
  note: string
}) {
  const words = WORDS[target.kind]
  const refresh = useRefreshAll()
  const restore = useMutation({ mutationFn: () => api.restore(target), onSuccess: () => refresh() })
  return (
    <div className="archived-banner" role="status">
      <Archive size={20} strokeWidth={2.25} aria-hidden="true" />
      <span>
        <strong>
          {words.archive === 'Olvidar' ? 'Olvidado' : 'Archivado'} el {formatDateTime(archivedAt)}.
        </strong>{' '}
        {note}
      </span>
      <Button
        size="sm"
        variant="secondary"
        onClick={() => restore.mutate()}
        disabled={restore.isPending}
      >
        <ArchiveRestore size={16} strokeWidth={2.5} aria-hidden="true" />
        {words.restore}
      </Button>
      <ErrorMessage error={restore.error} />
    </div>
  )
}

/** Aviso cuando lo archivado es el padre (el trabajo o el proyecto) y no la página misma. */
export function InheritedArchiveBanner({ children }: { children: ReactNode }) {
  return (
    <div className="archived-banner" role="status">
      <Archive size={20} strokeWidth={2.25} aria-hidden="true" />
      <span>{children}</span>
    </div>
  )
}

/** Chip «Archivados» de una lista: con él, la lista enseña solo lo archivado. */
export function ArchivedToggle({
  on,
  onChange,
  label = 'Archivados',
}: {
  on: boolean
  onChange: (on: boolean) => void
  label?: string
}) {
  return (
    <button
      type="button"
      className={cn('chip', on && 'chip-on')}
      aria-pressed={on}
      onClick={() => onChange(!on)}
    >
      <Archive size={14} strokeWidth={2.5} aria-hidden="true" />
      {label}
    </button>
  )
}

/** Resultado de una acción en bloque: cuántas salieron y por qué falló cada una de las demás. */
interface BulkResult {
  done: number
  failures: { name: string; message: string }[]
}

/**
 * Barra de las ejecuciones seleccionadas: archivarlas o, si ya lo están, restaurarlas o
 * eliminarlas. Va una a una; las que fallan (p. ej. una en curso) se cuentan con su motivo.
 */
export function BulkRunActions({
  runs,
  archivedView,
  onDone,
}: {
  runs: { id: string; name: string }[]
  archivedView: boolean
  onDone: () => void
}) {
  const refresh = useRefreshAll()
  const [confirming, setConfirming] = useState(false)
  const run = useMutation({
    mutationFn: async (action: 'archive' | 'restore' | 'delete'): Promise<BulkResult> => {
      const result: BulkResult = { done: 0, failures: [] }
      for (const r of runs) {
        const target: ArchiveTarget = { kind: 'run', id: r.id }
        try {
          if (action === 'archive') await api.archive(target)
          else if (action === 'restore') await api.restore(target)
          else await api.delete(target)
          result.done++
        } catch (e) {
          result.failures.push({ name: r.name, message: (e as Error).message })
        }
      }
      return result
    },
    onSuccess: (result) => {
      setConfirming(false)
      if (result.failures.length === 0) onDone()
      void refresh()
    },
  })
  const n = runs.length
  const result = run.data

  return (
    <div className="bulk-bar" role="group" aria-label="Acciones con las ejecuciones seleccionadas">
      <span className="bulk-count">
        {n} {n === 1 ? 'seleccionada' : 'seleccionadas'}
      </span>
      {archivedView ? (
        <>
          <Button
            size="sm"
            variant="secondary"
            disabled={run.isPending}
            onClick={() => run.mutate('restore')}
          >
            <ArchiveRestore size={16} strokeWidth={2.5} aria-hidden="true" />
            Restaurar
          </Button>
          <Button
            size="sm"
            variant="secondary-danger"
            disabled={run.isPending}
            aria-haspopup="dialog"
            onClick={() => setConfirming(true)}
          >
            <Trash2 size={16} strokeWidth={2.5} aria-hidden="true" />
            Eliminar…
          </Button>
        </>
      ) : (
        <Button
          size="sm"
          variant="secondary"
          disabled={run.isPending}
          onClick={() => run.mutate('archive')}
        >
          <Archive size={16} strokeWidth={2.5} aria-hidden="true" />
          Archivar
        </Button>
      )}
      {result && result.failures.length > 0 && (
        <div className="delete-blockers" role="alert">
          <p>
            <strong>
              {result.done > 0 && `${result.done} hechas. `}
              {result.failures.length === 1
                ? 'Una no se pudo:'
                : `${result.failures.length} no se pudieron:`}
            </strong>
          </p>
          <ul>
            {result.failures.map((f) => (
              <li key={f.name}>
                {f.name}: {f.message}
              </li>
            ))}
          </ul>
        </div>
      )}
      <Sheet
        open={confirming}
        onOpenChange={setConfirming}
        title={n === 1 ? 'Eliminar 1 ejecución' : `Eliminar ${n} ejecuciones`}
      >
        <div className="delete-panel">
          <p>
            Se borra cada ejecución con sus fases, agentes, prompts, verificaciones, artefactos y
            eventos. No tiene vuelta atrás.
          </p>
          <p className="muted small">
            Las que aún tengan worktrees en su runner no se eliminan: ábrelas para eliminar antes
            sus worktrees.
          </p>
          <div className="form-actions">
            <Button variant="danger" disabled={run.isPending} onClick={() => run.mutate('delete')}>
              <Trash2 size={18} strokeWidth={2.5} aria-hidden="true" />
              {run.isPending ? 'Eliminando…' : 'Eliminar definitivamente'}
            </Button>
            <Button variant="secondary" onClick={() => setConfirming(false)}>
              Cancelar
            </Button>
          </div>
        </div>
      </Sheet>
    </div>
  )
}
