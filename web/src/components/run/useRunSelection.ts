import { useCallback } from 'react'
import { useSearchParams } from 'react-router'
import type { TimelineFilters } from '../timeline/timeline'

export const INSPECTOR_TABS = [
  { id: 'summary', label: 'Resumen' },
  { id: 'prompt', label: 'Prompt' },
  { id: 'tools', label: 'Herramientas' },
  { id: 'artifacts', label: 'Artefactos' },
  { id: 'verification', label: 'Verificación' },
  { id: 'cost', label: 'Coste' },
  { id: 'event', label: 'Evento original' },
] as const

export type InspectorTab = (typeof INSPECTOR_TABS)[number]['id']

/** Pestañas del panel central: lo que hace la ejecución, contado de tres maneras. */
export const ACTIVITY_VIEWS = [
  { id: 'conversation', label: 'Conversación' },
  { id: 'waterfall', label: 'Cascada' },
  { id: 'events', label: 'Eventos' },
] as const

export type ActivityView = (typeof ACTIVITY_VIEWS)[number]['id']

/** En el móvil, los tres paneles pasan a ser pestañas. */
export type RunPane = 'tree' | 'activity' | 'inspector'

const isTab = (v: string | null): v is InspectorTab => INSPECTOR_TABS.some((t) => t.id === v)
const isView = (v: string | null): v is ActivityView => ACTIVITY_VIEWS.some((t) => t.id === v)
const isPane = (v: string | null): v is RunPane =>
  v === 'tree' || v === 'activity' || v === 'inspector'

export interface RunSelection {
  /** Agente elegido en la URL; null si no hay ninguno (la página elige uno por defecto). */
  agentId: string | null
  tab: InspectorTab
  /** Pestaña del panel central. */
  view: ActivityView
  /** Llamada a una herramienta elegida (su `toolUseId`), para abrirla en el inspector. */
  toolUseId: string | null
  /** Panel visible en el móvil. */
  pane: RunPane
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
  const view = params.get('view')
  const pane = params.get('pane')
  const selection: RunSelection = {
    agentId: params.get('agent'),
    tab: isTab(tab) ? tab : 'summary',
    // Conversación era una pestaña del inspector (`?tab=conversation`): ahora es la vista por
    // defecto del panel central, así que los enlaces antiguos siguen llevando a ella.
    view: isView(view) ? view : 'conversation',
    toolUseId: params.get('tool'),
    pane: isPane(pane) ? pane : 'activity',
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
          put('view', change.view === 'conversation' ? null : change.view)
          put('tool', change.toolUseId)
          put('pane', change.pane === 'activity' ? null : change.pane)
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
