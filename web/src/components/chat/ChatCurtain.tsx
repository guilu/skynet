import * as Dialog from '@radix-ui/react-dialog'
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useLocation } from 'react-router'
import { api, ApiError, type ChatAction, type ChatThread } from '../../api'
import { formatDateTime } from '../../format'
import { useMediaQuery } from '../../useMediaQuery'
import { Companion } from '../companion/Companion'
import { SkynyLive } from '../companion/SkynyLive'
import { ErrorMessage } from '../ErrorMessage'
import { Icon } from '../icons/Icon'
import { ChatMessages } from './ChatMessages'
import {
  CHAT_LED,
  chatContext,
  chatExpression,
  chatStatus,
  DECIDE_KEY,
  failedTurn,
  hasActions,
  MOMENT_MS,
  modelName,
  suggestions,
  type ChatMoment,
} from './chatContext'

/** La última conversación abierta: al volver a abrir la cortina, sigue en ella. */
const LAST_KEY = 'skynet.chat.conversation'

function readLast(): string | null {
  try {
    return localStorage.getItem(LAST_KEY)
  } catch {
    return null
  }
}

function writeLast(id: string | null) {
  try {
    if (id) localStorage.setItem(LAST_KEY, id)
    else localStorage.removeItem(LAST_KEY)
  } catch {
    // Sin almacenamiento: la conversación dura lo que la pestaña.
  }
}

/** Mientras Skyny contesta, por si el stream se corta, se pregunta de vez en cuando. */
const ANSWERING_POLL_MS = 4000

/**
 * La cortina del chat con Skyny (CH-D): baja desde la barra superior sobre la web atenuada, con
 * Skyny grande y un prompt. Cada mensaje lo contesta un turno de Skyny en un runner (CH-C); lo que
 * cambie algo llega como una tarjeta que la persona confirma o cancela. Las conversaciones se
 * guardan en Skynet y se retoman desde «Conversaciones». Skyny pone cara de lo que pasa (CH-E):
 * piensa mientras contesta, avisa si espera una confirmación, celebra lo hecho y lamenta lo fallido.
 */
export function ChatCurtain({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { pathname } = useLocation()
  const queryClient = useQueryClient()
  const mobile = useMediaQuery('(max-width: 720px)')
  const [conversationId, setConversationId] = useState<string | null>(readLast)
  const [view, setView] = useState<'chat' | 'history'>('chat')
  const [text, setText] = useState('')
  const input = useRef<HTMLTextAreaElement>(null)
  const bottom = useRef<HTMLDivElement>(null)

  const choose = (id: string | null) => {
    setConversationId(id)
    writeLast(id)
    setView('chat')
  }

  const thread = useQuery({
    queryKey: ['chat', conversationId],
    queryFn: async () => {
      try {
        return await api.chatThread(conversationId!)
      } catch (e) {
        // Una conversación que ya no existe (o de otra base de datos): se empieza otra.
        if (e instanceof ApiError && e.status === 404) {
          writeLast(null)
          return null
        }
        throw e
      }
    },
    enabled: open && conversationId != null,
    refetchInterval: (q) =>
      q.state.data?.conversation.state === 'ANSWERING' ? ANSWERING_POLL_MS : false,
  })
  // La conversación de verdad: ninguna si la guardada ya no existe.
  const activeId = thread.data === null ? null : conversationId
  const withActions = hasActions(thread.data ?? undefined)
  const actions = useQuery({
    queryKey: ['chat-actions', activeId],
    queryFn: () => api.chatActions(activeId!),
    enabled: open && activeId != null && withActions,
  })
  const history = useQuery({
    queryKey: ['chat-conversations'],
    queryFn: () => api.chatConversations(),
    enabled: open && view === 'history',
  })

  // Lo que pasa en la conversación llega por el stream de eventos: mensajes y acciones.
  useEffect(() => {
    if (!open || !activeId || typeof EventSource === 'undefined') return
    const params = new URLSearchParams({
      aggregateId: activeId,
      types: 'chat.*',
      after: 'latest',
    })
    const source = new EventSource(`/api/events/stream?${params}`)
    source.onmessage = () => {
      void queryClient.invalidateQueries({ queryKey: ['chat', activeId] })
      void queryClient.invalidateQueries({ queryKey: ['chat-actions', activeId] })
    }
    return () => source.close()
  }, [open, activeId, queryClient])

  const send = useMutation({
    mutationFn: async (message: string): Promise<ChatThread> => {
      if (activeId) {
        await api.sendChat(activeId, message)
        return api.chatThread(activeId)
      }
      return api.startChat(message, chatContext(pathname))
    },
    onSuccess: (data) => {
      queryClient.setQueryData(['chat', data.conversation.id], data)
      if (data.conversation.id !== activeId) choose(data.conversation.id)
      void queryClient.invalidateQueries({ queryKey: ['chat-conversations'] })
    },
  })
  const stop = useMutation({
    mutationFn: () => api.stopChat(activeId!),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['chat', activeId] }),
  })

  const conversation = thread.data?.conversation
  const messages = thread.data?.messages ?? []
  const pending = send.isPending ? (send.variables ?? null) : null
  const answering = conversation?.state === 'ANSWERING'
  const talking = view === 'chat' && (messages.length > 0 || pending != null)

  // Lo que acaba de pasar (una acción hecha o fallida, un turno que no pudo contestar) se ve un
  // momento en la cara de Skyny y luego vuelve a lo de siempre.
  const [moment, setMoment] = useState<{ state: ChatMoment; key: number } | null>(null)
  const flash = (state: ChatMoment) => setMoment((m) => ({ state, key: (m?.key ?? 0) + 1 }))
  useEffect(() => {
    if (!moment) return
    const timer = setTimeout(() => setMoment(null), MOMENT_MS)
    return () => clearTimeout(timer)
  }, [moment])
  // Solo cuenta un mensaje que llega con la cortina delante, no el de una conversación retomada.
  const lastId = thread.data?.messages.at(-1)?.id
  const [seen, setSeen] = useState({ conversation: activeId, last: lastId })
  if (seen.conversation !== activeId || seen.last !== lastId) {
    setSeen({ conversation: activeId, last: lastId })
    if (seen.conversation === activeId && seen.last !== undefined && failedTurn(messages))
      flash('error')
  }
  const onDecided = (action: ChatAction) => {
    if (action.status === 'CONFIRMED') flash('success')
    else if (action.status === 'FAILED') flash('error')
  }
  const deciding = useIsMutating({ mutationKey: DECIDE_KEY }) > 0

  useEffect(() => {
    bottom.current?.scrollIntoView?.({ block: 'end' })
  }, [messages.length, pending, actions.data])

  const submit = (message: string) => {
    const body = message.trim()
    if (!body || send.isPending || answering) return
    setText('')
    send.mutate(body)
  }
  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    submit(text)
  }
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit(text)
    }
  }
  const startNew = () => {
    choose(null)
    send.reset()
    setText('')
    input.current?.focus()
  }

  const size = mobile ? (talking ? 150 : 180) : 240
  const expression = chatExpression({
    conversation,
    actions: actions.data,
    sending: send.isPending,
    deciding,
    failed: send.error != null || stop.error != null,
    moment: moment?.state ?? null,
  })

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="curtain-overlay" />
        <Dialog.Content
          className="curtain"
          data-talking={talking || undefined}
          aria-describedby={undefined}
          onOpenAutoFocus={(e) => {
            e.preventDefault()
            // En el móvil no: el teclado saldría a media bajada y movería la página.
            if (!mobile) input.current?.focus({ preventScroll: true })
          }}
        >
          <div className="curtain-head">
            <Dialog.Title className="curtain-title">
              {talking && conversation ? conversation.title : 'Chat con Skyny'}
              {talking && conversation?.workItemKey && (
                <span className="muted"> · {conversation.workItemKey}</span>
              )}
              {talking && !conversation?.workItemKey && conversation?.projectKey && (
                <span className="muted"> · {conversation.projectKey}</span>
              )}
            </Dialog.Title>
            <button
              type="button"
              className="curtain-ghost"
              aria-pressed={view === 'history'}
              onClick={() => setView(view === 'history' ? 'chat' : 'history')}
            >
              Conversaciones
            </button>
            <button type="button" className="curtain-ghost" onClick={startNew}>
              Nueva
            </button>
            <Dialog.Close className="curtain-ghost" aria-label="Cerrar">
              <Icon name="close" size={18} strokeWidth={2.5} />
            </Dialog.Close>
          </div>

          <div className="curtain-skyny">
            <SkynyLive
              state={talking ? expression : 'calm'}
              size={size}
              message={talking ? CHAT_LED[expression] : undefined}
              fallback={
                <Companion state={talking ? expression : 'calm'} size={Math.round(size * 0.8)} />
              }
            />
          </div>

          {view === 'history' ? (
            <div className="curtain-history">
              <h2>Conversaciones</h2>
              {history.isPending && <p className="muted">Cargando…</p>}
              <ErrorMessage error={history.error} />
              {history.data?.length === 0 && (
                <p className="muted">Todavía no has hablado con Skyny.</p>
              )}
              <ul>
                {history.data?.map((c) => (
                  <li key={c.id}>
                    <button
                      type="button"
                      aria-current={c.id === conversationId || undefined}
                      onClick={() => choose(c.id)}
                    >
                      <strong>{c.title}</strong>
                      <span className="muted">
                        {[c.workItemKey ?? c.projectKey, formatDateTime(c.updatedAt)]
                          .filter(Boolean)
                          .join(' · ')}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ) : talking ? (
            <>
              <p className="curtain-status" role="status">
                {chatStatus(expression)}
              </p>
              <div className="curtain-thread" aria-live="polite">
                <ChatMessages
                  conversationId={activeId}
                  messages={messages}
                  actions={actions.data}
                  pending={pending}
                  offline={conversation?.state === 'OFFLINE' && !send.isPending}
                  onNavigate={() => onOpenChange(false)}
                  onDecided={onDecided}
                />
                <div ref={bottom} />
              </div>
            </>
          ) : (
            <div className="curtain-hero">
              <h2>¿En qué te ayudo?</h2>
              <p>
                Te cuento qué pasa, busco en la memoria y lanzo workflows. Lo que cambie algo te lo
                pregunto antes.
              </p>
            </div>
          )}

          {view === 'chat' && (
            <>
              <ErrorMessage error={send.error ?? stop.error ?? thread.error} />
              <form className="curtain-prompt" onSubmit={onSubmit}>
                <textarea
                  ref={input}
                  rows={1}
                  value={text}
                  maxLength={8000}
                  placeholder="Pregúntale a Skyny"
                  aria-label="Mensaje para Skyny"
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={onKeyDown}
                />
                <span className="curtain-model">{modelName(conversation?.model)}</span>
                {answering ? (
                  <button
                    type="button"
                    className="curtain-send"
                    aria-label="Parar"
                    disabled={stop.isPending}
                    onClick={() => stop.mutate()}
                  >
                    <Icon name="status-paused" size={18} strokeWidth={2.5} />
                  </button>
                ) : (
                  <button
                    type="submit"
                    className="curtain-send"
                    aria-label="Enviar"
                    disabled={!text.trim() || send.isPending}
                  >
                    ↑
                  </button>
                )}
              </form>
              <p className="curtain-meta">
                {modelName(conversation?.model)} · las conversaciones se guardan en Skynet
              </p>
              {!talking && (
                <ul className="curtain-suggest">
                  {suggestions(pathname).map((s) => (
                    <li key={s.text}>
                      <button type="button" onClick={() => submit(s.text)}>
                        <i aria-hidden="true">{s.icon}</i>
                        {s.text}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
          <div className="curtain-grip" aria-hidden="true" />
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
