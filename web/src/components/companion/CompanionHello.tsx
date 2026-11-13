import { useQuery } from '@tanstack/react-query'
import { CirclePlay, ListChecks, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '../../lib/cn'
import { api, type ApprovalSummary } from '../../api'
import { useMediaQuery } from '../../useMediaQuery'
import { useNow } from '../../useNow'
import { Companion } from './Companion'
import { useCompanion } from './context'
import { usePoke } from './gesture'
import { helloText, successStreak } from './peek'
import { SkynyLive } from './SkynyLive'

/** Cuántas ejecuciones recientes mira para la racha. */
const STREAK_RUNS = 20

/** Skyny 3D en la banda (SV-C): más pequeño en el móvil, donde va encima del texto. */
const SIZE_3D = 140
const SIZE_3D_NARROW = 96
const SIZE_2D = 80

/**
 * Tarjeta de saludo de Inicio: la mascota cuenta qué hay en marcha y qué te espera, con atajos
 * a lo que ya existe (revisar la aprobación, lanzar un workflow, ver la última ejecución). El
 * saludo cambia con la hora y cuenta la racha sin fallos; al pulsar a Skyny dice una frase (SY-C).
 * Desde SV-C es el único Skyny de Inicio: en 3D (con el 2D de respaldo) y con el estado global
 * del companion, así que escribe en su pantalla lo que pasa en todo el espacio de trabajo.
 */
export function CompanionHello({
  approvals,
  sweat,
}: {
  approvals: ApprovalSummary[]
  /** Alguna cuota aprieta: en 2D, Skyny suda. */
  sweat?: boolean
}) {
  const { activeRuns, expression, gesture } = useCompanion()
  const narrow = useMediaQuery('(max-width: 720px)')
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
        <SkynyLive
          state={expression}
          size={narrow ? SIZE_3D_NARROW : SIZE_3D}
          fallback={
            <Companion
              state={expression}
              size={SIZE_2D}
              gesture={poked.gesture ?? gesture}
              sweat={sweat}
              look
            />
          }
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
