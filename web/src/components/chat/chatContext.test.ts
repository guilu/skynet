import { describe, expect, it } from 'vitest'
import { chatContext, chatStatus, modelName, suggestions } from './chatContext'

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
    expect(chatStatus(undefined, undefined, true)).toBe('Pensando…')
    expect(chatStatus(undefined, undefined, false)).toBe('En reposo')
  })
})
