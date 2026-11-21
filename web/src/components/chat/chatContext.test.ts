import { describe, expect, it } from 'vitest'
import type { ChatAction, ChatConversation, ChatMessage } from '../../api'
import { LED_GLYPHS } from '../companion/three/screen'
import {
  CHAT_LED,
  chatContext,
  chatExpression,
  chatStatus,
  failedTurn,
  modelName,
  suggestions,
} from './chatContext'

const ID = '11111111-1111-4111-8111-111111111111'

describe('chatContext', () => {
  it('una conversación nueva va del trabajo o del proyecto que se está viendo', () => {
    expect(chatContext(`/work-items/${ID}`)).toEqual({ workItemId: ID })
    expect(chatContext(`/projects/${ID}`)).toEqual({ projectId: ID })
    expect(chatContext('/projects/import')).toEqual({})
    expect(chatContext('/')).toEqual({})
  })

  it('las sugerencias dependen de la página', () => {
    expect(suggestions(`/work-items/${ID}`)[0].text).toBe('¿En qué punto está este trabajo?')
    expect(suggestions(`/projects/${ID}`)[0].text).toBe('¿Qué hay en marcha en este proyecto?')
    expect(suggestions(`/runs/${ID}`)[0].text).toBe('¿Cómo va esta ejecución?')
    expect(suggestions('/')).toHaveLength(3)
  })

  it('el estado de Skyny y el nombre del modelo', () => {
    expect(modelName('haiku')).toBe('Haiku')
    expect(modelName(undefined)).toBe('Haiku')
    expect(chatStatus('thinking')).toBe('Pensando…')
    expect(chatStatus('calm')).toBe('En reposo')
  })

  it('la cara de Skyny sigue al turno, de lo más urgente a lo más tranquilo', () => {
    const conv = (state: ChatConversation['state']) => ({ state }) as ChatConversation
    const pending = [{ status: 'PENDING' }] as ChatAction[]
    const base = {
      conversation: conv('IDLE'),
      actions: undefined,
      sending: false,
      deciding: false,
      failed: false,
      moment: null,
    }
    expect(chatExpression(base)).toBe('calm')
    expect(chatExpression({ ...base, actions: pending })).toBe('waiting')
    expect(chatExpression({ ...base, conversation: conv('OFFLINE') })).toBe('offline')
    expect(chatExpression({ ...base, actions: pending, moment: 'success' })).toBe('success')
    expect(chatExpression({ ...base, moment: 'success', deciding: true })).toBe('running')
    expect(chatExpression({ ...base, conversation: conv('ANSWERING'), deciding: true })).toBe(
      'thinking',
    )
    expect(chatExpression({ ...base, conversation: conv('OFFLINE'), sending: true })).toBe(
      'thinking',
    )
    expect(chatExpression({ ...base, sending: true, failed: true })).toBe('error')
  })

  it('reconoce un turno que no pudo contestar y los letreros se pueden escribir', () => {
    const msg = (author: ChatMessage['author'], body: string) => ({ author, body }) as ChatMessage
    expect(failedTurn([msg('SKYNY', 'No he podido contestar: se acabó el tiempo')])).toBe(true)
    expect(failedTurn([msg('SKYNY', 'Hay dos ejecuciones en marcha.')])).toBe(false)
    expect(failedTurn([msg('USER', 'No he podido contestar')])).toBe(false)
    expect(failedTurn([])).toBe(false)
    for (const text of Object.values(CHAT_LED))
      for (const char of text) if (char !== ' ') expect(LED_GLYPHS[char], char).toBeDefined()
  })
})
