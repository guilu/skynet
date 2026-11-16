import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import Markdown from 'react-markdown'
import { Link } from 'react-router'
import remarkGfm from 'remark-gfm'
import { api, type ChatAction, type ChatCard, type ChatMessage } from '../../api'
import { formatTime } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { safeLink } from '../run/artifacts'
import { Button } from '../ui/Button'
import { ACTION_CONFIRM, ACTION_QUESTION, DANGEROUS, DECIDE_KEY } from './chatContext'

/**
 * Los mensajes de una conversación con Skyny: los de la persona a la derecha, los de Skyny en
 * markdown (sin HTML ni imágenes) con sus tarjetas debajo. `pending` es el mensaje que se está
 * enviando, para verlo en el acto.
 */
export function ChatMessages({
  conversationId,
  messages,
  actions,
  pending,
  offline,
  onNavigate,
  onDecided,
}: {
  conversationId: string | null
  messages: ChatMessage[]
  actions: ChatAction[] | undefined
  pending: string | null
  offline: boolean
  onNavigate: () => void
  /** Una acción recién confirmada o rechazada, con cómo ha quedado. */
  onDecided?: (action: ChatAction) => void
}) {
  return (
    <>
      {messages.map((m) =>
        m.author === 'USER' ? (
          <div key={m.id} className="chat-user">
            {m.body}
          </div>
        ) : (
          <div key={m.id} className="chat-skyny">
            <SkynyText text={m.body} onNavigate={onNavigate} />
            {m.cards.map((card) =>
              card.kind === 'action' && conversationId ? (
                <ActionCard
                  key={card.id}
                  conversationId={conversationId}
                  card={card}
                  action={actions?.find((a) => a.id === card.id)}
                  onNavigate={onNavigate}
                  onDecided={onDecided}
                />
              ) : (
                <ResultCard key={card.id} card={card} onNavigate={onNavigate} />
              ),
            )}
          </div>
        ),
      )}
      {pending && <div className="chat-user chat-sending">{pending}</div>}
      {offline && (
        <p className="chat-note" role="status">
          Ahora no hay ningún runner que atienda el chat. Tu mensaje queda guardado: vuelve a
          escribir cuando haya uno en línea.
        </p>
      )}
    </>
  )
}

/** Lo que dice Skyny. Los enlaces internos (`/runs/…`) llevan a la página y cierran la cortina. */
function SkynyText({ text, onNavigate }: { text: string; onNavigate: () => void }) {
  return (
    <div className="chat-text markdown">
      <Markdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        urlTransform={(url) => (url.startsWith('/') && !url.startsWith('//') ? url : safeLink(url))}
        components={{
          a: ({ href, children }) => {
            if (href?.startsWith('/')) {
              return (
                <Link to={href} onClick={onNavigate}>
                  {children}
                </Link>
              )
            }
            return href ? (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            ) : (
              <span>{children}</span>
            )
          },
          img: ({ alt }): ReactNode => <span className="muted">[imagen: {alt}]</span>,
        }}
      >
        {text}
      </Markdown>
    </div>
  )
}

/** Una tarjeta con enlace: la ejecución lanzada, el trabajo creado… */
function ResultCard({ card, onNavigate }: { card: ChatCard; onNavigate: () => void }) {
  return (
    <div className="chat-card">
      {card.url ? (
        <Link to={card.url} onClick={onNavigate} className="chat-card-link">
          {card.label} →
        </Link>
      ) : (
        <span>{card.label}</span>
      )}
    </div>
  )
}

/**
 * Lo que Skyny propone y no hace hasta que la persona lo confirma (CH-B): la pregunta, el resumen
 * y Confirmar o Cancelar. Una vez decidida, dice quién y cuándo, y enlaza lo que se hizo.
 */
function ActionCard({
  conversationId,
  card,
  action,
  onNavigate,
  onDecided,
}: {
  conversationId: string
  card: ChatCard
  action: ChatAction | undefined
  onNavigate: () => void
  onDecided?: (action: ChatAction) => void
}) {
  const queryClient = useQueryClient()
  const decide = useMutation({
    mutationKey: DECIDE_KEY,
    mutationFn: (confirm: boolean) =>
      confirm
        ? api.confirmChatAction(conversationId, card.id)
        : api.rejectChatAction(conversationId, card.id),
    onSuccess: (updated) => {
      queryClient.setQueryData<ChatAction[]>(['chat-actions', conversationId], (list) =>
        list?.map((a) => (a.id === updated.id ? updated : a)),
      )
      onDecided?.(updated)
    },
  })
  const pending = action?.status === 'PENDING'
  return (
    <div className={pending ? 'chat-card chat-card-ask' : 'chat-card'}>
      {action && pending && <h3>{ACTION_QUESTION[action.kind]}</h3>}
      <p className="chat-card-summary">{action?.summary ?? card.label}</p>
      {!action ? (
        <small className="muted">Cargando…</small>
      ) : pending ? (
        <div className="chat-card-foot">
          <Button
            variant={DANGEROUS.has(action.kind) ? 'danger' : 'primary'}
            disabled={decide.isPending}
            onClick={() => decide.mutate(true)}
          >
            {ACTION_CONFIRM[action.kind]}
          </Button>
          <Button
            variant="secondary"
            disabled={decide.isPending}
            onClick={() => decide.mutate(false)}
          >
            Cancelar
          </Button>
          <small>Skyny no hace nada hasta que confirmas.</small>
        </div>
      ) : (
        <Decided action={action} onNavigate={onNavigate} />
      )}
      <ErrorMessage error={decide.error} />
    </div>
  )
}

function Decided({ action, onNavigate }: { action: ChatAction; onNavigate: () => void }) {
  const when = action.decidedAt ? ` · ${formatTime(action.decidedAt)}` : ''
  switch (action.status) {
    case 'CONFIRMED':
      return (
        <div className="chat-card-foot">
          <span className="pill pill-ok">Confirmada{when}</span>
          {action.result?.url && (
            <Link to={action.result.url} onClick={onNavigate} className="chat-card-link">
              {action.result.label} →
            </Link>
          )}
        </div>
      )
    case 'REJECTED':
      return (
        <div className="chat-card-foot">
          <span className="pill">Cancelada{when}</span>
          <small>No se ha hecho nada.</small>
        </div>
      )
    case 'EXPIRED':
      return (
        <div className="chat-card-foot">
          <span className="pill">Caducada</span>
          <small>Pasó una hora sin respuesta: pídesela otra vez a Skyny.</small>
        </div>
      )
    case 'FAILED':
      return (
        <div className="chat-card-foot">
          <span className="pill pill-bad">No se pudo</span>
          <small>{action.error}</small>
        </div>
      )
    default:
      return null
  }
}
