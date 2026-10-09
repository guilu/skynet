import { useMutation, useQuery } from '@tanstack/react-query'
import { Bot, ChevronDown, User, Wrench } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, type AgentRun, type ConversationTurn, type StoredEvent } from '../../api'
import { formatCost, formatTime, isTerminal, toolSummary, workspaceUsable } from '../../format'
import { ErrorMessage } from '../ErrorMessage'
import { StatusBadge } from '../StatusBadge'
import { messagesOf, toolCallsOf, type ToolCall } from './agentEvents'
import { JsonView } from './JsonView'
import { LongText } from './LongText'
import { Button } from '../ui/Button'
import { Pill } from '../ui/Pill'

const KIND_LABELS: Record<AgentRun['kind'], string> = {
  START: 'Lanzamiento',
  RESUME: 'Reanudación',
  RETRY: 'Reintento',
  FORK: 'Fork',
}

interface Message {
  sequence: number
  occurredAt: string
  text: string
}

/**
 * Conversación de la sesión del agente (ADR-0001 §3.8): las invocaciones encadenadas, cada una con
 * tu mensaje y las respuestas del agente, y una caja para continuarla. Las invocaciones de otras
 * ejecuciones salen de `GET /api/agent-runs/{id}/conversation`; los mensajes del agente elegido
 * llegan en vivo por el stream. El texto se pinta siempre como texto.
 */
export function Conversation({
  agent,
  events,
  onShowEvent,
}: {
  agent: AgentRun
  events: StoredEvent[]
  onShowEvent: (sequence: number) => void
}) {
  const conversation = useQuery({
    queryKey: ['conversation', agent.id, agent.status],
    queryFn: () => api.conversation(agent.id),
    // Mientras alguna invocación siga viva, sus mensajes y su estado cambian.
    refetchInterval: (query) =>
      query.state.data?.turns.some((t) => !isTerminal(t.agent.status)) ? 3000 : false,
  })
  const live: Message[] = messagesOf(events)
    .filter((m) => m.parentToolUseId == null)
    .map(({ sequence, occurredAt, text }) => ({ sequence, occurredAt, text }))
  const calls = toolCallsOf(events).filter((c) => c.parentToolUseId == null)
  const loaded = conversation.data?.turns ?? []
  const turns: ConversationTurn[] = loaded.some((t) => t.agent.id === agent.id)
    ? loaded
    : [...loaded, { workflowRunId: '', agent, prompt: null, messages: [] }]
  const last = turns[turns.length - 1].agent

  return (
    <>
      <ol className="conversation">
        {turns.map((turn) => {
          const current = turn.agent.id === agent.id
          // Del agente elegido, lo que ya ha llegado por el stream va por delante de la consulta, y
          // sus herramientas se intercalan con los mensajes en el orden en que llegaron.
          const messages = current && live.length > 0 ? live : turn.messages
          const items: Item[] = [
            ...messages.map((m) => ({
              kind: 'message' as const,
              sequence: m.sequence,
              message: m,
            })),
            ...(current
              ? calls.map((c) => ({ kind: 'tool' as const, sequence: c.startedSequence, call: c }))
              : []),
          ].sort((a, b) => a.sequence - b.sequence)
          return (
            <li key={turn.agent.id} className={current ? 'turn current' : 'turn'}>
              <TurnHeader turn={turn} current={current} />
              {turn.prompt && (
                <div className="msg msg-user">
                  <span className="avatar avatar-user" aria-hidden="true">
                    <User size={20} />
                  </span>
                  <div className="bubble bubble-user">
                    <p className="bubble-meta">Tú</p>
                    <LongText text={turn.prompt} />
                  </div>
                </div>
              )}
              {items.map((item) =>
                item.kind === 'message' ? (
                  <div key={item.sequence} className="msg">
                    <span className="avatar avatar-agent" aria-hidden="true">
                      <Bot size={20} />
                    </span>
                    <div className="bubble bubble-agent">
                      <p className="bubble-meta">
                        Agente · {formatTime(item.message.occurredAt)}
                        {current && (
                          <>
                            {' · '}
                            <Button
                              type="button"
                              variant="link"
                              onClick={() => onShowEvent(item.sequence)}
                            >
                              Ver evento #{item.sequence}
                            </Button>
                          </>
                        )}
                      </p>
                      <LongText text={item.message.text} />
                    </div>
                  </div>
                ) : (
                  <ToolCard key={item.call.toolUseId} call={item.call} onShowEvent={onShowEvent} />
                ),
              )}
              {items.length === 0 && (
                <p className="muted small">
                  {isTerminal(turn.agent.status) ? 'Sin mensajes.' : 'Sin mensajes todavía.'}
                </p>
              )}
            </li>
          )
        })}
      </ol>
      <ErrorMessage error={conversation.error} />
      <Composer last={last} />
    </>
  )
}

type Item =
  | { kind: 'message'; sequence: number; message: Message }
  | { kind: 'tool'; sequence: number; call: ToolCall }

/** Llamada a una herramienta dentro de la conversación, plegada: nombre, argumento y estado. */
function ToolCard({
  call,
  onShowEvent,
}: {
  call: ToolCall
  onShowEvent: (sequence: number) => void
}) {
  const running = call.completedSequence == null
  const tone = running ? 'active' : call.isError ? 'bad' : 'ok'
  return (
    <details className="tool-card">
      <summary>
        <span className={`tool-card-icon icon-${tone}`} aria-hidden="true">
          <Wrench size={16} />
        </span>
        <span className="tool-card-name">
          <strong>{call.name}</strong>
          <span className="tool-card-arg">{toolSummary(call.input).replace(/^: /, '')}</span>
        </span>
        <Pill tone={tone}>{running ? 'En curso' : call.isError ? 'Error' : 'Hecha'}</Pill>
        <ChevronDown size={18} className="tool-card-chevron" aria-hidden="true" />
      </summary>
      <div className="tool-card-body">
        <JsonView value={call.input} label="Entrada" />
        <p className="muted small">Salida</p>
        {call.output == null ? (
          <p className="muted small">{running ? 'En curso…' : 'Sin salida.'}</p>
        ) : (
          <LongText text={call.output} code />
        )}
        <Button type="button" variant="link" onClick={() => onShowEvent(call.startedSequence)}>
          Ver evento #{call.startedSequence}
        </Button>
      </div>
    </details>
  )
}

function TurnHeader({ turn, current }: { turn: ConversationTurn; current: boolean }) {
  const label = KIND_LABELS[turn.agent.kind]
  return (
    <p className="turn-header small">
      <strong>{label}</strong> <StatusBadge status={turn.agent.status} />{' '}
      <span className="muted">{formatCost(turn.agent.costUsd)}</span>
      {current ? (
        <span className="muted"> · esta invocación</span>
      ) : (
        turn.workflowRunId && (
          <>
            {' · '}
            <Link to={`/runs/${turn.workflowRunId}?agent=${turn.agent.id}`}>Ver invocación</Link>
          </>
        )
      )}
    </p>
  )
}

/**
 * Caja para continuar la sesión desde su última invocación. Solo se activa cuando esa invocación
 * ha terminado y su sesión llegó a arrancar; dice dónde continuará. Enviar no pide confirmación:
 * sigue el mismo trabajo en el mismo worktree.
 */
function Composer({ last }: { last: AgentRun }) {
  const navigate = useNavigate()
  const [text, setText] = useState('')
  const runners = useQuery({
    queryKey: ['runners'],
    queryFn: api.runners,
    enabled: !!last.workspace,
  })
  const send = useMutation({
    mutationFn: () => api.sendMessage(last.id, text),
    onSuccess: (run) => {
      setText('')
      void navigate(`/runs/${run.id}`)
    },
  })
  const ready =
    isTerminal(last.status) && !!last.providerSessionId && workspaceUsable(last.workspace)
  const runner = runners.data?.find((r) => r.id === last.workspace?.runnerId)

  function submit(e: FormEvent) {
    e.preventDefault()
    if (text.trim()) send.mutate()
  }

  return (
    <form className="composer form" onSubmit={submit} aria-label="Continuar la conversación">
      <label>
        Mensaje
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={3}
          disabled={!ready || send.isPending}
          placeholder={ready ? 'Pide al agente que siga…' : undefined}
        />
      </label>
      {ready ? (
        <p className="muted small">
          Continúa la sesión en el worktree <code>{last.workspace!.path}</code> (rama{' '}
          <code>{last.workspace!.branch}</code>) del runner{' '}
          {runner?.name ?? last.workspace!.runnerId.slice(0, 8)}.
        </p>
      ) : (
        <p className="muted small">
          {!isTerminal(last.status)
            ? 'Podrás escribir cuando termine la invocación en curso.'
            : last.workspace?.removedAt
              ? 'El worktree de esta sesión se eliminó: no se puede continuar.'
              : last.workspace?.cleanupRequestedAt
                ? 'El worktree de esta sesión se está eliminando: no se puede continuar.'
                : 'La sesión no llegó a arrancar: no se puede continuar.'}
        </p>
      )}
      <Button type="submit" disabled={!ready || !text.trim() || send.isPending}>
        {send.isPending ? 'Enviando…' : 'Enviar'}
      </Button>
      <ErrorMessage error={send.error} />
    </form>
  )
}
