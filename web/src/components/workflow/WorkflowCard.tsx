import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Archive, ArchiveRestore, Ellipsis, Eye } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api, PHASES, type Phase, type WorkflowSummary } from '../../api'
import { formatDateTime } from '../../format'
import { cn } from '../../lib/cn'
import { useMediaQuery } from '../../useMediaQuery'
import { ArchiveMenu } from '../archive/Archive'
import { PHASE_LABEL } from '../graph/layout'
import { Sheet } from '../ui/Sheet'
import { overviewChips, plural, providerInfo } from './overview'
import { VersionPill } from './status'

/** Workflow implícito de la Fase 1, que no se archiva. */
export const ADHOC = 'adhoc'

/** Abreviatura de cada etapa en la tira de la tarjeta. */
const PHASE_SHORT: Record<Phase, string> = {
  spec: 'Espe.',
  plan: 'Plan.',
  implement: 'Impl.',
  test: 'Prob.',
  review: 'Revi.',
  deliver: 'Entr.',
}

/** Las seis etapas, marcadas las que tienen alguna fase; la lectura de pantalla dice cuáles. */
function StageStrip({ lanes }: { lanes: string[] }) {
  const used = PHASES.filter((p) => lanes.includes(p))
  return (
    <div className="wf-strip">
      <ol className="wf-strip-steps" aria-hidden="true">
        {PHASES.map((p) => (
          <li key={p} className={cn(lanes.includes(p) && 'on')}>
            {PHASE_SHORT[p]}
          </li>
        ))}
      </ol>
      <span className="visually-hidden">
        Etapas: {used.length > 0 ? used.map((p) => PHASE_LABEL[p]).join(', ') : 'sin indicar'}.
      </span>
    </div>
  )
}

function Providers({ providers }: { providers: string[] }) {
  if (providers.length === 0) return null
  const info = providers.map((p) => ({ id: p, ...providerInfo(p) }))
  return (
    <p className="wf-card-providers">
      <span className="wf-monos" aria-hidden="true">
        {info.map((p) => (
          <span key={p.id} className={cn('wf-mono', `wf-mono-${p.id}`)}>
            {p.mono}
          </span>
        ))}
      </span>
      <span>
        <span className="visually-hidden">Proveedores: </span>
        {info.map((p) => p.name).join(' · ')}
      </span>
    </p>
  )
}

/**
 * Tarjeta de un workflow (WF-C): toda ella es el enlace a su página. Cuenta qué etapas cubre, sus
 * fases, aprobaciones, jueces, si entrega por PR o solo lee, qué proveedores usa, sus versiones,
 * los trabajos pendientes y la última actualización. Las acciones van en «⋯»: un menú en
 * escritorio y una hoja inferior en el móvil.
 */
export function WorkflowCard({
  workflow: w,
  pending,
}: {
  workflow: WorkflowSummary
  pending: number
}) {
  const narrow = useMediaQuery('(max-width: 720px)')
  const o = w.overview
  const updated = formatDateTime((w.draft ?? w.published)?.updatedAt ?? w.createdAt)
  const href = `/workflows/${encodeURIComponent(w.key)}`
  return (
    <li className={cn('wf-card', w.archivedAt && 'row-archived')}>
      <div className="wf-card-head">
        <Link to={href} className="wf-card-link">
          <span className="wf-card-name">{w.name ?? w.key}</span>
          {w.name && <span className="key">{w.key}</span>}
        </Link>
        {w.key !== ADHOC &&
          (narrow ? (
            <CardActions workflow={w} href={href} />
          ) : (
            <ArchiveMenu
              size="sm"
              target={{ kind: 'workflow', id: w.key }}
              name={w.name ?? w.key}
              archivedAt={w.archivedAt}
            />
          ))}
      </div>
      <p className="wf-card-desc">{w.description ?? 'Sin descripción.'}</p>
      {o && (
        <>
          <StageStrip lanes={o.lanes} />
          <ul className="wf-card-chips" aria-label="Qué hace">
            {overviewChips(o).map(({ icon: Icon, text }) => (
              <li key={text}>
                <Icon size={15} strokeWidth={2.25} aria-hidden="true" />
                {text}
              </li>
            ))}
          </ul>
          <Providers providers={o.providers} />
        </>
      )}
      <div className="wf-card-foot">
        <div className="wf-card-versions">
          {w.published ? (
            <VersionPill version={w.published} />
          ) : (
            <span className="muted small">Sin publicar</span>
          )}
          {w.draft && <VersionPill version={w.draft} />}
        </div>
        <p className="wf-card-when muted small">
          {pending > 0 && `${plural(pending, 'trabajo pendiente', 'trabajos pendientes')} · `}
          <span className="visually-hidden">Actualizado </span>
          {updated}
        </p>
      </div>
    </li>
  )
}

/** Las acciones de una tarjeta en el móvil: una hoja inferior con botones grandes. */
function CardActions({ workflow: w, href }: { workflow: WorkflowSummary; href: string }) {
  const [open, setOpen] = useState(false)
  const queryClient = useQueryClient()
  const target = { kind: 'workflow' as const, id: w.key }
  const change = useMutation({
    mutationFn: () => (w.archivedAt == null ? api.archive(target) : api.restore(target)),
    onSuccess: () => {
      setOpen(false)
      void queryClient.invalidateQueries()
    },
  })
  const name = w.name ?? w.key
  return (
    <>
      <button
        type="button"
        className="icon-btn wf-card-more"
        aria-label={`Más acciones: ${name}`}
        onClick={() => setOpen(true)}
      >
        <Ellipsis size={20} strokeWidth={2.5} aria-hidden="true" />
      </button>
      <Sheet open={open} onOpenChange={setOpen} title={name}>
        <ul className="wf-actions">
          <li>
            <SheetAction icon={<Eye size={20} aria-hidden="true" />}>
              <Link to={href}>Ver qué hace</Link>
            </SheetAction>
          </li>
          <li>
            <SheetAction
              icon={
                w.archivedAt == null ? (
                  <Archive size={20} aria-hidden="true" />
                ) : (
                  <ArchiveRestore size={20} aria-hidden="true" />
                )
              }
            >
              <button type="button" disabled={change.isPending} onClick={() => change.mutate()}>
                {w.archivedAt == null ? 'Archivar' : 'Restaurar'}
              </button>
            </SheetAction>
          </li>
        </ul>
      </Sheet>
    </>
  )
}

function SheetAction({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <span className="wf-action">
      {icon}
      {children}
    </span>
  )
}
