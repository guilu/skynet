import { useQuery } from '@tanstack/react-query'
import { CirclePlay, ListChecks, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '../../lib/cn'
import { api, type ApprovalSummary } from '../../api'
import { useNow } from '../../useNow'
import { Companion } from './Companion'
import { useCompanion } from './context'
import { usePoke } from './gesture'
import { helloText, successStreak } from './peek'

/** Cuántas ejecuciones recientes mira para la racha. */
const STREAK_RUNS = 20

/**
 * Tarjeta de saludo del Dashboard: la mascota cuenta qué hay en marcha y qué te espera, con atajos
 * a lo que ya existe (revisar la aprobación, lanzar un workflow, ver la última ejecución). El
 * saludo cambia con la hora y cuenta la racha sin fallos; al pulsar a Skyny dice una frase (SY-C).
 */
export function CompanionHello({ approvals }: { approvals: ApprovalSummary[] }) {
  const { activeRuns, gesture } = useCompanion()
  const latest = activeRuns[0]
  const now = useNow(true, 60_000)
  const recent = useQuery({
    queryKey: ['runs', 'list', 'streak'],
    queryFn: () => api.listRuns({ size: STREAK_RUNS }),
    refetchInterval: 60_000,
  })
  const streak = successStreak(recent.data?.items ?? [])
  const poked = usePoke()
  return (
    <section className="card companion-hello" aria-label="Skyny">
      <button
        type="button"
        className="skyny-poke"
        aria-label="Hablar con Skyny"
        onClick={poked.poke}
      >
        <Companion
          state={approvals.length > 0 ? 'waiting' : 'calm'}
          size={80}
          gesture={poked.gesture ?? gesture}
          look
        />
      </button>
      <div className="companion-hello-text">
        <div aria-live="polite">
          {poked.quip ? (
            <p>
              <strong>{poked.quip}</strong>
            </p>
          ) : (
            <Hello
              text={helloText(
                activeRuns.length,
                approvals.length,
                new Date(now).getHours(),
                streak,
              )}
            />
          )}
        </div>
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

/** El saludo («Hola, soy Skyny.») en negrita y el resto, normal. */
function Hello({ text }: { text: string }) {
  const cut = text.indexOf('. ') + 1
  return (
    <p>
      <strong>{text.slice(0, cut)}</strong>
      {text.slice(cut)}
    </p>
  )
}
