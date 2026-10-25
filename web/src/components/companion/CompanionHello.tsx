import { CirclePlay, ListChecks, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '../../lib/cn'
import type { ApprovalSummary } from '../../api'
import { Companion } from './Companion'
import { useCompanion } from './context'
import { helloText } from './peek'

/**
 * Tarjeta de saludo del Dashboard: la mascota cuenta qué hay en marcha y qué te espera, con atajos
 * a lo que ya existe (revisar la aprobación, lanzar un workflow, ver la última ejecución).
 */
export function CompanionHello({ approvals }: { approvals: ApprovalSummary[] }) {
  const { activeRuns } = useCompanion()
  const latest = activeRuns[0]
  return (
    <section className="card companion-hello" aria-label="Skynet">
      <Companion state={approvals.length > 0 ? 'waiting' : 'ready'} size={80} />
      <div className="companion-hello-text">
        <Hello text={helloText(activeRuns.length, approvals.length)} />
        <div className="companion-hello-links">
          {approvals.length > 0 && (
            <Link
              className="btn btn-sm"
              to={approvals.length === 1 ? `/approvals/${approvals[0].id}` : '/approvals'}
            >
              <ShieldCheck size={16} strokeWidth={2.5} aria-hidden="true" />
              {approvals.length === 1 ? 'Revisar la aprobación' : 'Revisar las aprobaciones'}
            </Link>
          )}
          <Link
            className={cn('btn btn-sm', approvals.length > 0 && 'btn-secondary')}
            to="/projects"
          >
            <CirclePlay size={16} strokeWidth={2.5} aria-hidden="true" />
            Lanzar un workflow
          </Link>
          <Link className="btn btn-sm btn-secondary" to={latest ? `/runs/${latest.id}` : '/runs'}>
            <ListChecks size={16} strokeWidth={2.5} aria-hidden="true" />
            {latest ? 'Ver la última ejecución' : 'Ver las ejecuciones'}
          </Link>
        </div>
      </div>
    </section>
  )
}

/** El saludo («Hola, soy Skynet.») en negrita y el resto, normal. */
function Hello({ text }: { text: string }) {
  const cut = text.indexOf('. ') + 1
  return (
    <p>
      <strong>{text.slice(0, cut)}</strong>
      {text.slice(cut)}
    </p>
  )
}
