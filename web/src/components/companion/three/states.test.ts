import { describe, expect, it } from 'vitest'
import { COMPANION_STATES } from '../state'
import { BACKS, FACES, HATS } from './extras'
import { retryMessage } from './transitions'
import { LED_GLYPHS } from './screen'
import { LED_COMPACT, LED_MESSAGES, STATE_CONFIG, STATES_META } from './states'

describe('estados de Skyny 3D (estudio v4.3)', () => {
  it('hay 34 estados, cada uno con su aspecto y su letrero', () => {
    expect(STATES_META).toHaveLength(34)
    for (const { state } of STATES_META) {
      expect(STATE_CONFIG[state], state).toBeDefined()
      expect(LED_MESSAGES[state], state).toBeTruthy()
    }
  })

  it('incluye todos los estados del companion de la web', () => {
    const names = new Set<string>(STATES_META.map((s) => s.state))
    for (const { state } of COMPANION_STATES) expect(names.has(state), state).toBe(true)
  })

  it('todas las letras de los letreros tienen su trazo', () => {
    const texts = [
      ...Object.values(LED_MESSAGES),
      ...Object.values(LED_COMPACT),
      retryMessage(1, 2),
    ]
    for (const text of texts)
      for (const char of text)
        if (char !== ' ') expect(LED_GLYPHS[char], `${char} en ${text}`).toBeDefined()
  })

  it('los complementos empiezan por «sin» y no repiten valor', () => {
    for (const list of [HATS, FACES, BACKS]) {
      expect(list[0].value).toBe('')
      expect(new Set(list.map((o) => o.value)).size).toBe(list.length)
    }
  })
})
