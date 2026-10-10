import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FilePen, Rocket, Save, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { api, ApiError, type DefinitionDetail, type VersionView } from '../api'
import { ArchivedBanner, ArchiveMenu } from '../components/archive/Archive'
import { ErrorMessage } from '../components/ErrorMessage'
import { Button } from '../components/ui/Button'
import { Sheet } from '../components/ui/Sheet'
import { LinesSkeleton } from '../components/ui/Skeleton'
import { DefinitionEditor } from '../components/workflow/DefinitionEditor'
import { ImportButton } from '../components/workflow/ImportButton'
import { ProblemList } from '../components/workflow/ProblemList'
import { VersionPill } from '../components/workflow/status'
import { formatDateTime } from '../format'
import { cn } from '../lib/cn'
import { ADHOC } from './WorkflowsPage'

/**
 * Un workflow: sus versiones y, de la elegida (`?version=`; por defecto el borrador o la última
 * publicada), el YAML. Un borrador se edita, se guarda, se publica o se descarta; una versión
 * publicada solo se lee, y «Editar» abre el borrador de la siguiente.
 */
export function WorkflowPage() {
  const { key = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const workflow = useQuery({ queryKey: ['workflow', key], queryFn: () => api.workflow(key) })
  const versions = workflow.data?.versions ?? []
  const summary = workflow.data?.workflow
  const chosen =
    versions.find((v) => v.id === params.get('version')) ??
    versions.find((v) => v.status !== 'PUBLISHED') ??
    versions[0]
  const archivedAt = summary?.archivedAt ?? null

  const choose = (id: string) => setParams({ version: id }, { replace: true })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['workflow', key] })
    void queryClient.invalidateQueries({ queryKey: ['workflows'] })
    void queryClient.invalidateQueries({ queryKey: ['workflow-definitions'] })
  }
  const edit = useMutation({
    mutationFn: () => api.draftWorkflow(key),
    onSuccess: (draft) => {
      queryClient.setQueryData(['workflow-version', draft.id], draft)
      refresh()
      choose(draft.id)
    },
  })
  const hasDraft = versions.some((v) => v.status !== 'PUBLISHED')

  return (
    <>
      <ErrorMessage error={workflow.error} />
      <div className="page-head">
        <div className="page-title">
          <h1>{summary?.name ?? key}</h1>
          <p className="muted small">
            <code>{key}</code>
            {summary?.published &&
              ` · versión publicada v${summary.published.version} desde el ${formatDateTime(summary.published.publishedAt)}`}
            {summary && !summary.published && ' · aún sin publicar'}
          </p>
          {summary?.description && <p>{summary.description}</p>}
        </div>
        <div className="page-actions">
          {summary && !hasDraft && archivedAt == null && (
            <Button onClick={() => edit.mutate()} disabled={edit.isPending}>
              <FilePen size={18} strokeWidth={2.5} aria-hidden="true" />
              Editar
            </Button>
          )}
          {summary && key !== ADHOC && (
            <ArchiveMenu
              target={{ kind: 'workflow', id: key }}
              name={summary.name ?? key}
              archivedAt={archivedAt}
            />
          )}
        </div>
      </div>
      <ErrorMessage error={edit.error} />
      {archivedAt && (
        <ArchivedBanner
          target={{ kind: 'workflow', id: key }}
          archivedAt={archivedAt}
          note="No sale al lanzar y su borrador no se puede editar."
        />
      )}
      {versions.length > 0 && <VersionTabs versions={versions} chosen={chosen} onChoose={choose} />}
      {workflow.isPending && <LinesSkeleton label="el workflow" lines={8} />}
      {chosen && (
        <VersionPanel
          key={chosen.id}
          id={chosen.id}
          editable={chosen.status !== 'PUBLISHED' && archivedAt == null}
          onChanged={refresh}
          onDiscarded={(gone) => {
            refresh()
            if (gone) void navigate('/workflows')
            else setParams({}, { replace: true })
          }}
          onlyVersion={versions.length === 1}
        />
      )}
    </>
  )
}

/** Versiones del workflow, de la más reciente a la más antigua. */
function VersionTabs({
  versions,
  chosen,
  onChoose,
}: {
  versions: VersionView[]
  chosen: VersionView | undefined
  onChoose: (id: string) => void
}) {
  return (
    <nav className="version-tabs" aria-label="Versiones">
      {versions.map((v) => (
        <button
          key={v.id}
          type="button"
          className={cn('chip', v.id === chosen?.id && 'chip-on')}
          aria-current={v.id === chosen?.id ? 'page' : undefined}
          onClick={() => onChoose(v.id)}
        >
          <VersionPill version={v} />
          <span className="muted small">{formatDateTime(v.publishedAt ?? v.updatedAt)}</span>
        </button>
      ))}
    </nav>
  )
}

/** El YAML de una versión; si es un borrador editable, con guardar, publicar y descartar. */
function VersionPanel({
  id,
  editable,
  onChanged,
  onDiscarded,
  onlyVersion,
}: {
  id: string
  editable: boolean
  onChanged: () => void
  /** `gone`: era la única versión y el workflow ya no existe. */
  onDiscarded: (gone: boolean) => void
  onlyVersion: boolean
}) {
  const queryClient = useQueryClient()
  const detail = useQuery({
    queryKey: ['workflow-version', id],
    queryFn: () => api.workflowVersion(id),
  })
  // Lo escrito, ligado a la revisión sobre la que se escribió: al guardar (o si otro guarda) se
  // vuelve a partir del texto guardado.
  const [edited, setEdited] = useState<{ stamp: string; text: string } | null>(null)
  const [publishing, setPublishing] = useState(false)
  const [discarding, setDiscarding] = useState(false)
  const saved = detail.data
  const stamp = saved ? `${saved.id}:${saved.revision}` : ''
  const text = edited?.stamp === stamp ? edited.text : (saved?.sourceYaml ?? null)
  const setText = (next: string) => setEdited({ stamp, text: next })

  const store = (next: DefinitionDetail) => {
    queryClient.setQueryData(['workflow-version', id], next)
    onChanged()
  }
  const save = useMutation({
    mutationFn: () => api.saveWorkflowVersion(id, text ?? '', saved!.revision),
    onSuccess: store,
  })
  const publish = useMutation({
    mutationFn: () => api.publishWorkflowVersion(id, saved!.revision),
    onSuccess: (next) => {
      store(next)
      setPublishing(false)
    },
  })
  const discard = useMutation({
    mutationFn: () => api.discardWorkflowVersion(id),
    onSuccess: () => {
      setDiscarding(false)
      queryClient.removeQueries({ queryKey: ['workflow-version', id] })
      onDiscarded(onlyVersion)
    },
  })

  if (detail.error) return <ErrorMessage error={detail.error} />
  if (!saved || text == null) return <LinesSkeleton label="la versión" lines={8} />

  const dirty = text !== saved.sourceYaml
  const isDraft = saved.status !== 'PUBLISHED'
  const canPublish = editable && !dirty && saved.validation.publishable
  const publishHint = !isDraft
    ? null
    : dirty
      ? 'Guarda los cambios antes de publicar.'
      : !saved.validation.valid
        ? 'Corrige los errores para publicar.'
        : !saved.validation.publishable
          ? 'Usa algo que el motor aún no ejecuta: todavía no se puede publicar.'
          : null

  return (
    <>
      {isDraft && (
        <div className="version-status">
          <span className="muted small" aria-live="polite">
            {dirty ? 'Cambios sin guardar' : `Guardado el ${formatDateTime(saved.updatedAt)}`}
          </span>
          {publishHint && <span className="muted small">{publishHint}</span>}
        </div>
      )}
      <DefinitionEditor
        text={text}
        onChange={editable ? setText : undefined}
        readOnly={!editable}
        saved={{
          text: saved.sourceYaml,
          validation: saved.validation,
          definition: saved.definition,
        }}
        validateAs={isDraft ? { key: saved.key, version: saved.version } : undefined}
        onSave={() => editable && dirty && save.mutate()}
        label={`YAML de ${saved.key} v${saved.version}`}
        actions={
          editable && (
            <>
              <Button onClick={() => save.mutate()} disabled={!dirty || save.isPending}>
                <Save size={18} strokeWidth={2.5} aria-hidden="true" />
                Guardar
              </Button>
              <Button variant="success" onClick={() => setPublishing(true)} disabled={!canPublish}>
                <Rocket size={18} strokeWidth={2.5} aria-hidden="true" />
                Publicar…
              </Button>
              <ImportButton onText={setText} />
              <Button variant="secondary-danger" onClick={() => setDiscarding(true)}>
                <Trash2 size={18} strokeWidth={2.5} aria-hidden="true" />
                Descartar borrador…
              </Button>
            </>
          )
        }
      />
      <ErrorMessage error={save.error} />

      <Sheet
        open={publishing}
        onOpenChange={setPublishing}
        title={`Publicar ${saved.key} v${saved.version}`}
        description="Una versión publicada ya no se puede cambiar ni borrar."
      >
        <div className="form">
          <p>
            A partir de ahora, las ejecuciones nuevas de <code>{saved.key}</code> usarán la versión{' '}
            {saved.version}. Las que ya corrieron siguen con la suya.
          </p>
          {saved.validation.problems.length > 0 && (
            <>
              <p className="muted small">Tiene avisos, que no impiden publicarla:</p>
              <ProblemList problems={saved.validation.problems} />
            </>
          )}
          <div className="form-actions">
            <Button variant="success" onClick={() => publish.mutate()} disabled={publish.isPending}>
              <Rocket size={18} strokeWidth={2.5} aria-hidden="true" />
              Publicar v{saved.version}
            </Button>
            <Button variant="secondary" onClick={() => setPublishing(false)}>
              Cancelar
            </Button>
          </div>
          <ErrorMessage error={publish.error} />
          {publish.error instanceof ApiError && publish.error.problems.length > 0 && (
            <ProblemList problems={publish.error.problems} />
          )}
        </div>
      </Sheet>

      <Sheet
        open={discarding}
        onOpenChange={setDiscarding}
        title={`Descartar el borrador v${saved.version}`}
        description={saved.key}
      >
        <div className="form">
          <p>
            {onlyVersion
              ? 'Es la única versión: el workflow desaparece con ella.'
              : 'Se pierden sus cambios; las versiones publicadas no se tocan.'}
          </p>
          <div className="form-actions">
            <Button variant="danger" onClick={() => discard.mutate()} disabled={discard.isPending}>
              <Trash2 size={18} strokeWidth={2.5} aria-hidden="true" />
              Descartar
            </Button>
            <Button variant="secondary" onClick={() => setDiscarding(false)}>
              Cancelar
            </Button>
          </div>
          <ErrorMessage error={discard.error} />
        </div>
      </Sheet>
    </>
  )
}
