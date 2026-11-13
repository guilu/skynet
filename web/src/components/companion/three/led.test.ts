import { describe, expect, it } from 'vitest'
import { COMPANION_STATES } from '../state'
import { LED_GLYPHS, LED_MESSAGES, ledDuration, ledMessage, ledTypingFrame } from './led'
import { retryMessage } from './transitions'

describe('letreros de la pantalla', () => {
  it('hay uno para cada estado y todas sus letras tienen trazo', () => {
    for (const { state } of COMPANION_STATES) {
      const text = LED_MESSAGES[state]
      expect(text).toBeTruthy()
      for (const char of [...text, ...ledMessage(state, true)])
        if (char !== ' ') expect(LED_GLYPHS[char], `${state}: «${char}»`).toBeDefined()
    }
  })

  it('los contadores y los reintentos se pueden escribir', () => {
    for (const char of '0123456789/') expect(LED_GLYPHS[char]).toBeDefined()
    for (const char of retryMessage(2, 3)) if (char !== ' ') expect(LED_GLYPHS[char]).toBeDefined()
  })

  it('en compacto, los letreros largos se acortan', () => {
    for (const { state } of COMPANION_STATES)
      expect([...ledMessage(state, true)].length).toBeLessThanOrEqual(9)
    expect(ledMessage('thinking', true)).toBe('IDEA')
    expect(ledMessage('thinking', false)).toBe('PENSANDO...')
  })

  it('escribe letra a letra con cursor y acaba un segundo después', () => {
    expect(ledTypingFrame(0, 4, false)).toEqual({ count: 0, cursor: true, finished: false })
    expect(ledTypingFrame(200, 4, false).count).toBe(2)
    expect(ledTypingFrame(ledDuration('HOLA') - 1, 4, false).finished).toBe(false)
    expect(ledTypingFrame(ledDuration('HOLA'), 4, false).finished).toBe(true)
  })

  it('con movimiento reducido sale entero y sin cursor', () => {
    expect(ledTypingFrame(0, 4, true)).toEqual({ count: 4, cursor: false, finished: false })
  })
})
