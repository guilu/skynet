import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatAction, ChatConversation, ChatMessage, ChatThread } from '../../api'
import { mockFetch, renderAt } from '../../test/render'
import { ChatCurtain } from './ChatCurtain'

const ID = '11111111-1111-4111-8111-111111111111'
const WORK_ITEM = '22222222-2222-4222-8222-222222222222'
const ACTION = '33333333-3333-4333-8333-333333333333'
const RUN = '44444444-4444-4444-8444-444444444444'

const conversation = (over: Partial<ChatConversation> = {}): ChatConversation => ({
  id: ID,
  title: '¿Qué hay en marcha?',
  projectId: null,
  projectKey: null,
  projectName: null,
  workItemId: null,
  workItemKey: null,
  workItemTitle: null,
  provider: 'claude-code',
  model: 'haiku',
  state: 'IDLE',
  messageCount: 2,
  createdAt: '2026-11-16T19:00:00Z',
  createdBy: 'admin',
  updatedAt: '2026-11-16T19:01:00Z',
  archivedAt: null,
  ...over,
})

const message = (seq: number, author: ChatMessage['author'], body: string, cards = []) =>
  ({
    id: `m${seq}`,
    conversationId: ID,
    seq,
    author,
    body,
    cards,
    createdAt: '2026-11-16T19:00:00Z',
  }) as ChatMessage

const action = (over: Partial<ChatAction> = {}): ChatAction => ({
  id: ACTION,
  conversationId: ID,
  kind: 'LAUNCH',
  input: {},
  summary: 'Lanzar implementar-tarea en DEMO-4',
  status: 'PENDING',
  result: null,
  error: null,
  createdAt: '2026-11-16T19:00:00Z',
  decidedAt: null,
  decidedBy: null,
  ...over,
})

function stub(routes: Record<string, unknown>) {
  const fetch = vi.fn(mockFetch(routes))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const posted = (fetch: ReturnType<typeof vi.fn>, path: string) =>
  fetch.mock.calls
    .filter(([url, init]) => url === path && (init as RequestInit)?.method === 'POST')
    .map(([, init]) => JSON.parse(String((init as RequestInit).body ?? 'null')))

describe('La cortina del chat con Skyny', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  it('sin conversación saluda, sugiere según la página y la primera pregunta la crea', async () => {
    const thread: ChatThread = {
      conversation: conversation({ state: 'OFFLINE', workItemKey: 'DEMO-4', messageCount: 1 }),
      messages: [message(1, 'USER', '¿En qué punto está este trabajo?')],
    }
    const fetch = stub({
      'POST /api/conversations': thread,
      [`/api/conversations/${ID}`]: thread,
    })
    renderAt(`/work-items/${WORK_ITEM}`, <ChatCurtain open onOpenChange={() => {}} />)

    expect(screen.getByRole('heading', { name: '¿En qué te ayudo?' })).toBeVisible()
    expect(screen.getByRole('textbox', { name: 'Mensaje para Skyny' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: /¿En qué punto está este trabajo\?/ }))

    expect(await screen.findByText(/no hay ningún runner que atienda el chat/)).toBeVisible()
    expect(posted(fetch, '/api/conversations')).toEqual([
      { message: '¿En qué punto está este trabajo?', workItemId: WORK_ITEM },
    ])
    expect(screen.getByRole('dialog')).toHaveTextContent('¿Qué hay en marcha? · DEMO-4')
    expect(screen.getByText('Ningún runner me atiende ahora')).toBeVisible()
    expect(localStorage.getItem('skynet.chat.conversation')).toBe(ID)
  })

  it('retoma la última conversación y confirma lo que propone Skyny', async () => {
    localStorage.setItem('skynet.chat.conversation', ID)
    const card = { kind: 'action', id: ACTION, label: 'Lanzar implementar-tarea', url: null }
    const fetch = stub({
      [`/api/conversations/${ID}`]: {
        conversation: conversation(),
        messages: [
          message(1, 'USER', 'Lanza implementar-tarea en DEMO-4'),
          message(2, 'SKYNY', 'DEMO-4 no tiene nada en marcha. **¿Lo lanzo?**', [card] as never),
        ],
      },
      [`/api/conversations/${ID}/actions`]: [action()],
      [`POST /api/conversations/${ID}/actions/${ACTION}/confirm`]: action({
        status: 'CONFIRMED',
        decidedAt: '2026-11-16T19:02:00Z',
        decidedBy: 'admin vía chat',
        result: {
          kind: 'workflow_run',
          id: RUN,
          label: 'DEMO-4 · implementar-tarea',
          url: `/runs/${RUN}`,
        },
      }),
    })
    const close = vi.fn()
    renderAt('/', <ChatCurtain open onOpenChange={close} />)

    expect(await screen.findByText('¿Lo lanzo?', { selector: 'strong' })).toBeVisible()
    expect(await screen.findByRole('heading', { name: '¿Lo lanzo?' })).toBeVisible()
    expect(screen.getByText('Esperando tu confirmación')).toBeVisible()
    await waitFor(() =>
      expect(document.querySelector('.curtain-skyny [data-state]')).toHaveAttribute(
        'data-state',
        'waiting',
      ),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar y lanzar' }))

    const link = await screen.findByRole('link', { name: 'DEMO-4 · implementar-tarea →' })
    expect(link).toHaveAttribute('href', `/runs/${RUN}`)
    expect(screen.getByText(/^Confirmada · /)).toBeVisible()
    expect(screen.getByText('Hecho')).toBeVisible()
    await waitFor(() =>
      expect(document.querySelector('.curtain-skyny [data-state]')).toHaveAttribute(
        'data-state',
        'success',
      ),
    )
    expect(posted(fetch, `/api/conversations/${ID}/actions/${ACTION}/confirm`)).toHaveLength(1)
    fireEvent.click(link)
    expect(close).toHaveBeenCalledWith(false)
  })

  it('mientras Skyny contesta no deja escribir otro mensaje, pero sí pararlo', async () => {
    localStorage.setItem('skynet.chat.conversation', ID)
    const fetch = stub({
      [`/api/conversations/${ID}`]: {
        conversation: conversation({ state: 'ANSWERING' }),
        messages: [message(1, 'USER', '¿Qué me espera hoy?')],
      },
      [`POST /api/conversations/${ID}/stop`]: conversation(),
    })
    renderAt('/', <ChatCurtain open onOpenChange={() => {}} />)

    expect(await screen.findByText('Pensando…')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Enviar' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Parar' }))
    await waitFor(() => expect(posted(fetch, `/api/conversations/${ID}/stop`)).toHaveLength(1))
  })

  it('lista las conversaciones para retomar una, y «Nueva» empieza otra', async () => {
    const other = '55555555-5555-4555-8555-555555555555'
    stub({
      '/api/conversations?limit=20': [
        conversation({ id: other, title: 'Memoria de akademia', projectKey: 'AK' }),
      ],
      [`/api/conversations/${other}`]: {
        conversation: conversation({ id: other, title: 'Memoria de akademia' }),
        messages: [message(1, 'USER', '¿Qué decidimos?'), message(2, 'SKYNY', 'Sesiones.')],
      },
    })
    renderAt('/', <ChatCurtain open onOpenChange={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: 'Conversaciones' }))
    const item = await screen.findByRole('button', { name: /Memoria de akademia/ })
    expect(item).toHaveTextContent('AK')
    fireEvent.click(item)
    expect(await screen.findByText('Sesiones.')).toBeVisible()
    expect(localStorage.getItem('skynet.chat.conversation')).toBe(other)

    fireEvent.click(screen.getByRole('button', { name: 'Nueva' }))
    expect(screen.getByRole('heading', { name: '¿En qué te ayudo?' })).toBeVisible()
    expect(localStorage.getItem('skynet.chat.conversation')).toBeNull()
  })

  it('una conversación guardada que ya no existe deja empezar otra', async () => {
    localStorage.setItem('skynet.chat.conversation', ID)
    stub({})
    renderAt('/', <ChatCurtain open onOpenChange={() => {}} />)

    expect(screen.getByRole('heading', { name: '¿En qué te ayudo?' })).toBeVisible()
    await waitFor(() => expect(localStorage.getItem('skynet.chat.conversation')).toBeNull())
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
