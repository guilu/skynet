import { useCallback } from 'react'
import { useSearchParams } from 'react-router'
import type { TimelineFilters } from '../timeline/timeline'

export const INSPECTOR_TABS = [
  { id: 'summary', label: 'Resumen' },
  { id: 'prompt', label: 'Prompt' },
  { id: 'conversation', label: 'Conversación' },
  { id: 'tools', label: 'Herramientas' },
  { id: 'event', label: 'Evento original' },
] as const

export type InspectorTab = (typeof INSPECTOR_TABS)[number]['id']

const isTab = (v: string | null): v is InspectorTab => INSPECTOR_TABS.some((t) => t.id === v)
/** La pestaña Mensajes pasó a ser Conversación: los enlaces antiguos siguen funcionando. */
const tabOf = (v: string | null): InspectorTab =>
  v === 'messages' ? 'conversation' : isTab(v) ? v : 'summary'

export interface RunSelection {
  /** Agente elegido en la URL; null si no hay ninguno (la página elige uno por defecto). */
  agentId: string | null
  tab: InspectorTab
  /** Secuencia del evento elegido, para la pestaña Evento original. */
  sequence: number | null
  /** Filtros del timeline. */
  filters: TimelineFilters
}

const FILTER_KEYS = ['stage', 'agent', 'kind', 'severity', 'origin'] as const
const filterParam = (key: (typeof FILTER_KEYS)[number]) => `f.${key}`

/**
 * Selección de la página de una ejecución, guardada en la query string
 * (`?agent=…&tab=tools&seq=123&f.kind=tool`) para que se pueda enlazar y sobreviva a una recarga.
 */
export function useRunSelection(): [RunSelection, (change: Partial<RunSelection>) => void] {
  const [params, setParams] = useSearchParams()
  const seq = Number(params.get('seq'))
  const tab = params.get('tab')
  const selection: RunSelection = {
    agentId: params.get('agent'),
    tab: tabOf(tab),
    sequence: Number.isInteger(seq) && seq > 0 ? seq : null,
    filters: Object.fromEntries(
      FILTER_KEYS.flatMap((k) => {
        const v = params.get(filterParam(k))
        return v ? [[k, v]] : []
      }),
    ) as TimelineFilters,
  }

  const select = useCallback(
    (change: Partial<RunSelection>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          const put = (key: string, value: string | number | null | undefined) => {
            if (value === undefined) return
            if (value === null) next.delete(key)
            else next.set(key, String(value))
          }
          put('agent', change.agentId)
          put('tab', change.tab === 'summary' ? null : change.tab)
          put('seq', change.sequence)
          if (change.filters) {
            for (const k of FILTER_KEYS) put(filterParam(k), change.filters[k] ?? null)
          }
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  return [selection, select]
}
