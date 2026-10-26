import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { api, type Run, type RunPage, type StoredEvent } from '../../api'
import { useNow } from '../../useNow'
import type { StreamState } from '../../useEventStream'
import { CompanionContext, type CompanionContextValue } from './context'
import { useGesture } from './gesture'
import { usePendingApprovals } from '../approval/usePendingApprovals'
import { applyEvent } from '../run/applyEvent'
import {
  COMPANION_EVENT_TYPES,
  HISTORY,
  noticeFor,
  pushToast,
  summarize,
  type Notice,
  type Toast,
} from './notices'
import { useCompanionPrefs } from './prefs'
import { globalExpression, runExpression } from './state'
import { browserNotice, useTabSignals } from './tab'

/** Tras cuánto tiempo sin stream la mascota pasa a «Sin conexión». */
const OFFLINE_AFTER_MS = 5_000
/** Cuánto dura «Éxito» en la barra cuando algo termina bien. */
const SUCCESS_MS = 8_000
const ACTIVE_KEY = ['companion', 'active'] as const

/** La ejecución que se está mirando, para no avisar de ella (salvo aprobaciones). */
const viewedRun = (pathname: string) => /^\/runs\/([^/]+)/.exec(pathname)?.[1] ?? null

/**
 * El companion global: un único stream SSE para toda la web (solo lo nuevo y solo los tipos que
 * importan), las ejecuciones en marcha, las aprobaciones pendientes, la campana y los avisos.
 */
export function CompanionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const prefs = useCompanionPrefs()
  const active = useQuery({
    queryKey: ACTIVE_KEY,
    queryFn: () => api.listRuns({ status: ['PENDING', 'RUNNING'], size: 50 }),
    refetchInterval: 30_000,
  })
  const pending = usePendingApprovals()
  // Para la gotita de sudor: las cuotas que ya avisan en el Dashboard y en Uso.
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: api.dashboard,
    refetchInterval: 60_000,
    staleTime: 30_000,
  })
  const [streamState, setStreamState] = useState<StreamState>('connecting')
  const [offline, setOffline] = useState(false)
  const [notices, setNotices] = useState<Notice[]>([])
  const [toasts, setToasts] = useState<Toast[]>([])
  const [unseenErrors, setUnseenErrors] = useState<ReadonlySet<string>>(new Set())
  const [successUntil, setSuccessUntil] = useState(0)
  const now = useNow(true, 30_000)
  const [idleSince, setIdleSince] = useState(now)

  const viewed = viewedRun(pathname)
  // Lo que lee el manejador del stream sin reabrir la conexión.
  const live = useRef({ viewed, prefs })
  useEffect(() => {
    live.current = { viewed, prefs }
  })

  // Al abrir una ejecución fallida, su error ya está visto (se ajusta durante el render).
  if (viewed && unseenErrors.has(viewed))
    setUnseenErrors(new Set([...unseenErrors].filter((id) => id !== viewed)))

  const runs = useMemo(() => active.data?.items ?? [], [active.data])
  const pendingCount = pending.data?.length ?? 0
  const busy = runs.length > 0 || pendingCount > 0
  // Desde cuándo no hay nada en marcha ni pendiente: para dormirse a los 5 min.
  const [wasBusy, setWasBusy] = useState(busy)
  if (busy !== wasBusy) {
    setWasBusy(busy)
    setIdleSince(now)
  }

  useEffect(() => {
    if (successUntil === 0) return
    const t = window.setTimeout(() => setSuccessUntil(0), successUntil - Date.now())
    return () => window.clearTimeout(t)
  }, [successUntil])

  const refetchSoon = useRef<number | null>(null)
  const refetchActive = useCallback(() => {
    if (refetchSoon.current != null) return
    refetchSoon.current = window.setTimeout(() => {
      refetchSoon.current = null
      void queryClient.invalidateQueries({ queryKey: ACTIVE_KEY })
    }, 300)
  }, [queryClient])

  const notify = useCallback(
    (notice: Notice) => {
      const { viewed: watching, prefs: current } = live.current
      const quiet = notice.kind !== 'approval' && notice.runId != null && notice.runId === watching
      setNotices((list) => [{ ...notice, read: quiet }, ...list].slice(0, HISTORY))
      if (notice.kind === 'error' && notice.runId && !quiet)
        setUnseenErrors((s) => new Set(s).add(notice.runId!))
      if (notice.kind === 'success') setSuccessUntil(Date.now() + SUCCESS_MS)
      const muted = current.mutedUntil != null && current.mutedUntil > Date.now()
      if (muted) return
      // Con la pestaña oculta no la estás mirando: el aviso del navegador sale aunque sea de la
      // ejecución abierta.
      if (current.browser) browserNotice(notice, (href) => void navigate(href))
      if (!quiet && current.toasts) setToasts((t) => pushToast(t, notice))
    },
    [navigate],
  )

  const onEvent = useCallback(
    async (event: StoredEvent) => {
      const runId = event.workflowRunId
      if (event.type.startsWith('approval.')) {
        void queryClient.invalidateQueries({ queryKey: ['approvals'] })
        if (event.type !== 'approval.requested' && event.type !== 'approval.commented') {
          const id = String(event.payload.approvalId ?? '')
          setToasts((list) =>
            list
              .map((t) => ({ ...t, notices: t.notices.filter((n) => n.approvalId !== id) }))
              .filter((t) => t.notices.length > 0),
          )
          setNotices((list) => list.map((n) => (n.approvalId === id ? { ...n, read: true } : n)))
        }
      }
      // Cambios de estado de fases y agentes: se aplican a la ejecución en memoria.
      let cached: Run | undefined
      queryClient.setQueryData<RunPage>(ACTIVE_KEY, (page) => {
        if (!page) return page
        const items = page.items.map((run) => {
          if (run.id !== runId) return run
          const { run: next, refetch } = applyEvent(run, event)
          if (refetch !== 'none') refetchActive()
          cached = next
          return next
        })
        return { ...page, items }
      })
      if (!cached && runId && event.type !== 'approval.requested') refetchActive()

      const terminal = event.type === 'workflow.status.changed'
      if (terminal) void queryClient.invalidateQueries({ queryKey: ['runs'] })
      if (!noticeFor(event, null)) return
      // El aviso lleva la clave del trabajo y, al terminar, la duración, el coste y la fase.
      let run: Run | null = cached ?? null
      if (runId && (terminal || !run)) {
        run = await queryClient
          .fetchQuery({ queryKey: ['companion', 'run', runId], queryFn: () => api.run(runId) })
          .catch(() => null)
      }
      const notice = noticeFor(event, run)
      if (notice) notify(notice)
    },
    [queryClient, refetchActive, notify],
  )
  const handler = useRef(onEvent)
  useEffect(() => {
    handler.current = onEvent
  })

  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const params = new URLSearchParams({ after: 'latest', types: COMPANION_EVENT_TYPES.join(',') })
    const source = new EventSource(`/api/events/stream?${params}`)
    let offlineTimer: number | null = null
    let dropped = false
    source.onopen = () => {
      setStreamState('open')
      setOffline(false)
      if (offlineTimer != null) window.clearTimeout(offlineTimer)
      offlineTimer = null
      // Lo que pasó mientras no había stream se recupera releyendo el estado.
      if (dropped) {
        void queryClient.invalidateQueries({ queryKey: ACTIVE_KEY })
        void queryClient.invalidateQueries({ queryKey: ['approvals'] })
      }
    }
    source.onerror = () => {
      dropped = true
      setStreamState('reconnecting')
      offlineTimer ??= window.setTimeout(() => setOffline(true), OFFLINE_AFTER_MS)
    }
    source.onmessage = (message) => {
      void handler.current(JSON.parse(message.data as string) as StoredEvent)
    }
    return () => {
      source.close()
      if (offlineTimer != null) window.clearTimeout(offlineTimer)
    }
  }, [queryClient])

  const expression = globalExpression({
    offline,
    unseenError: unseenErrors.size > 0,
    pendingApprovals: pendingCount,
    active: runs.map((r) => runExpression(r, r.stages, now)),
    recentSuccess: successUntil > 0,
    idleMinutes: busy ? 0 : (now - idleSince) / 60_000,
  })

  // Dormida, se despierta con un bostezo al mover el ratón, teclear o volver a la pestaña.
  const [gesture, play] = useGesture()
  const asleep = expression === 'sleeping'
  useEffect(() => {
    if (!asleep) return
    const wake = () => {
      if (document.visibilityState === 'hidden') return
      setIdleSince(Date.now())
      play('yawn')
    }
    window.addEventListener('pointermove', wake)
    window.addEventListener('keydown', wake)
    document.addEventListener('visibilitychange', wake)
    return () => {
      window.removeEventListener('pointermove', wake)
      window.removeEventListener('keydown', wake)
      document.removeEventListener('visibilitychange', wake)
    }
  }, [asleep, play])

  const markRead = useCallback(() => {
    setNotices((list) =>
      list.some((n) => !n.read) ? list.map((n) => ({ ...n, read: true })) : list,
    )
    setUnseenErrors((s) => (s.size ? new Set() : s))
  }, [])
  const clearNotices = useCallback(() => setNotices([]), [])
  const dismissToast = useCallback(
    (id: string) => setToasts((list) => list.filter((t) => t.id !== id)),
    [],
  )

  const unread = notices.filter((n) => !n.read).length
  useTabSignals(expression, unread)

  const value: CompanionContextValue = {
    expression,
    summary: summarize(runs.length, pendingCount, offline),
    activeRuns: runs,
    pendingApprovals: pendingCount,
    notices,
    unread,
    markRead,
    clearNotices,
    toasts,
    dismissToast,
    streamState,
    gesture,
    quotaTight: (dashboard.data?.quotaWarnings.length ?? 0) > 0,
  }
  return <CompanionContext.Provider value={value}>{children}</CompanionContext.Provider>
}
