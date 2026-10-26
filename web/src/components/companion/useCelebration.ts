import { useEffect, useState } from 'react'
import type { RunStatus } from '../../api'
import { isTerminal } from '../../format'

/** Lo que dura el confeti a pantalla completa (SY-C). */
export const CELEBRATION_MS = 1_600

/**
 * Si la ejecución que estás mirando acaba de terminar bien: true durante `CELEBRATION_MS`. Solo
 * cuenta el paso de «en marcha» a «terminada bien» con la página abierta, no abrir una ya hecha.
 */
export function useCelebration(status: RunStatus | undefined): boolean {
  const [previous, setPrevious] = useState(status)
  const [on, setOn] = useState(false)
  // Se ajusta durante el render, como el resto de estados derivados de la web.
  if (status !== previous) {
    setPrevious(status)
    if (status === 'SUCCEEDED' && previous != null && !isTerminal(previous)) {
      setOn(true)
    }
  }
  useEffect(() => {
    if (!on) return
    const t = window.setTimeout(() => setOn(false), CELEBRATION_MS)
    return () => window.clearTimeout(t)
  }, [on])
  return on
}
