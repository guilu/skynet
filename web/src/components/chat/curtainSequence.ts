import { useCallback, useEffect, useState } from 'react'

/** Lo que tarda el Skyny flotante en irse volando y en volver. */
export const FLOAT_EXIT_MS = 1200
export const FLOAT_ENTER_MS = 1800
/** Lo que tarda la cortina en bajar y en subir (las animaciones `curtain-down` y `curtain-up`). */
export const CURTAIN_DOWN_MS = 450
export const CURTAIN_UP_MS = 250
/** Lo que tarda el Skyny de la cortina en entrar y en irse. */
export const CURTAIN_SKYNY_ENTER_MS = 1800
export const CURTAIN_SKYNY_EXIT_MS = 1200

/**
 * Por dónde va la cortina del chat. Una cosa detrás de otra:
 * - al abrir, `leaving`: se va volando el Skyny de la web; `open`: baja la cortina y, abajo del
 *   todo, entra volando el suyo;
 * - al cerrar, `returning`: se va volando el Skyny de la cortina; `raising`: sube la cortina;
 *   `closed`: vuelve volando el de la web.
 */
export type CurtainPhase = 'closed' | 'leaving' | 'open' | 'returning' | 'raising'

const NEXT: Partial<Record<CurtainPhase, CurtainPhase>> = {
  leaving: 'open',
  returning: 'raising',
  raising: 'closed',
}

/**
 * Encadena las animaciones de la cortina y de los dos Skyny. `floating` dice si el Skyny de la web
 * está a la vista (si no, no hay que esperarle) y `curtain3D`, si el de la cortina es el 3D.
 */
export function useCurtainSequence({
  floating,
  curtain3D,
}: {
  floating: boolean
  curtain3D: boolean
}) {
  const [phase, setPhase] = useState<CurtainPhase>('closed')

  useEffect(() => {
    const next = NEXT[phase]
    if (!next) return
    const ms =
      phase === 'leaving'
        ? floating
          ? FLOAT_EXIT_MS
          : 0
        : phase === 'returning'
          ? curtain3D
            ? CURTAIN_SKYNY_EXIT_MS
            : 0
          : CURTAIN_UP_MS
    const timer = window.setTimeout(() => setPhase(next), ms)
    return () => window.clearTimeout(timer)
  }, [phase, floating, curtain3D])

  /** Pide abrir o cerrar. A media animación, solo se puede deshacer la salida del de la web. */
  const request = useCallback((open: boolean) => {
    setPhase((p) => {
      if (open) return p === 'closed' ? 'leaving' : p
      if (p === 'open') return 'returning'
      if (p === 'leaving') return 'closed'
      return p
    })
  }, [])

  const toggle = useCallback(() => {
    setPhase((p) => (p === 'closed' ? 'leaving' : p === 'open' ? 'returning' : p))
  }, [])

  return {
    phase,
    /** La cortina está bajada (o a punto de subir, mientras se va su Skyny). */
    open: phase === 'open' || phase === 'returning',
    /** El Skyny de la web está fuera (o yéndose). */
    away: phase !== 'closed',
    /** El Skyny de la cortina se está yendo. */
    leaving: phase === 'returning',
    request,
    toggle,
  }
}
