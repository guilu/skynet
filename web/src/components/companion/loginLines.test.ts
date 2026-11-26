import { describe, expect, it } from 'vitest'
import { LED_GLYPHS } from './three/screen'
import {
  FAILED_LINE,
  greeting,
  HOUSE_LINES,
  loginTicker,
  PASSWORD_LINE,
  timelyLines,
} from './loginLines'

const at = (iso: string) => new Date(iso)

describe('letreros del login', () => {
  it('saluda según la hora', () => {
    expect(greeting(at('2026-11-21T09:00:00'))).toBe('BUENOS DÍAS')
    expect(greeting(at('2026-11-21T15:00:00'))).toBe('BUENAS TARDES')
    expect(greeting(at('2026-11-21T23:30:00'))).toBe('BUENAS NOCHES')
    expect(greeting(at('2026-11-21T03:00:00'))).toBe('BUENAS NOCHES')
  })

  it('los de madrugada, el café y el viernes solo cuando tocan', () => {
    expect(timelyLines(at('2026-11-21T03:00:00'))).toContain('404 SUEÑO NO ENCONTRADO')
    expect(timelyLines(at('2026-11-21T08:00:00'))).toEqual(['¿CAFÉ?'])
    expect(timelyLines(at('2026-11-20T17:00:00'))).toEqual(['NADA DE DEPLOYS EN VIERNES'])
    expect(timelyLines(at('2026-11-21T17:00:00'))).toEqual([])
  })

  it('el saludo va primero y la vuelta solo repite los de la casa', () => {
    const { lines, loopFrom } = loginTicker(at('2026-11-20T17:00:00'), () => 0.3)
    expect(lines.slice(0, 2)).toEqual(['BUENAS TARDES', 'NADA DE DEPLOYS EN VIERNES'])
    expect(loopFrom).toBe(2)
    expect([...lines.slice(loopFrom)].sort()).toEqual([...HOUSE_LINES].sort())
  })

  it('todas las letras tienen su trazo', () => {
    const texts = [
      ...HOUSE_LINES,
      ...PASSWORD_LINE,
      ...FAILED_LINE,
      'HOLA',
      ...['03', '08', '15', '22'].flatMap((h) => {
        const d = at(`2026-11-20T${h}:00:00`)
        return [greeting(d), ...timelyLines(d)]
      }),
    ]
    for (const text of texts)
      for (const char of text)
        if (char !== ' ') expect(LED_GLYPHS[char], `${char} en ${text}`).toBeDefined()
  })
})
