import { describe, expect, it } from 'vitest'
import indexCss from '../index.css?raw'
import { AA, contrast } from './palette'
import { tokenize, type YamlToken } from './yamlTokens'

/** Cada línea como `tipo:texto`, sin los espacios. */
const shape = (text: string) =>
  tokenize(text).map((tokens: YamlToken[]) =>
    tokens.filter((t) => t.type !== 'text').map((t) => `${t.type}:${t.text.trim()}`),
  )

describe('resaltado del YAML', () => {
  it('distingue claves, valores, literales y comentarios', () => {
    expect(shape('key: revisar # la clave\nmaxRetries: 2\nautoApprove: true')).toEqual([
      ['key:key', 'delimiter::', 'string:revisar', 'comment:# la clave'],
      ['key:maxRetries', 'delimiter::', 'literal:2'],
      ['key:autoApprove', 'delimiter::', 'literal:true'],
    ])
  })

  it('marca como referencia lo que nombra una fase o un agente', () => {
    const yaml = [
      'stages:',
      '  - id: corregir',
      '    agent: desarrollador',
      '    dependsOn: [reproducir, "sin-reproducir?"]',
      '    workspaceFrom: reproducir',
      '  - id: revisar',
      '    dependsOn:',
      '      - corregir',
      '    checks:',
      '      - commit',
    ].join('\n')
    const lines = shape(yaml)
    expect(lines[1]).toEqual(['delimiter:-', 'key:id', 'delimiter::', 'string:corregir'])
    expect(lines[2]).toContain('ref:desarrollador')
    expect(lines[3]).toEqual([
      'key:dependsOn',
      'delimiter::',
      'delimiter:[',
      'ref:reproducir',
      'delimiter:,',
      'ref:"sin-reproducir?"',
      'delimiter:]',
    ])
    expect(lines[4]).toContain('ref:reproducir')
    expect(lines[7]).toEqual(['delimiter:-', 'ref:corregir'])
    // `checks` no es una referencia: su lista vuelve a ser de valores.
    expect(lines[9]).toEqual(['delimiter:-', 'string:commit'])
  })

  it('pinta las variables dentro de un bloque y sale de él al bajar la sangría', () => {
    const lines = shape(
      [
        '    prompt: |',
        '      Corrige {{workItem.title}} y',
        '      mira {{ stages.reproducir.output }}.',
        '    agent: dev',
      ].join('\n'),
    )
    expect(lines[0]).toEqual(['key:prompt', 'delimiter::', 'delimiter:|'])
    expect(lines[1]).toEqual(['string:Corrige', 'variable:{{workItem.title}}', 'string:y'])
    expect(lines[2]).toContain('variable:{{ stages.reproducir.output }}')
    expect(lines[3]).toEqual(['key:agent', 'delimiter::', 'ref:dev'])
  })

  it('no pierde ni cambia ningún carácter', () => {
    const yaml = 'a:\n  - b: "c # d"  # e\n  - [x, y]\n\n# fin'
    expect(
      tokenize(yaml)
        .map((tokens) => tokens.map((t) => t.text).join(''))
        .join('\n'),
    ).toBe(yaml)
  })
})

/** Valor de un token en el primer bloque de index.css que empieza por `selector`. */
function tokenIn(selector: string, token: string): string {
  const block = indexCss.slice(indexCss.indexOf(selector))
  const value = new RegExp(`--${token}:\\s*(#[0-9a-f]{6});`).exec(
    block.slice(0, block.indexOf('}')),
  )?.[1]
  if (!value) throw new Error(`--${token} no está en ${selector}`)
  return value
}

describe('colores del código', () => {
  const THEMES = [':root {', ":root:not([data-theme='light'])", ":root[data-theme='dark']"]
  const TOKENS = ['fg', 'line', 'key', 'value', 'ref', 'literal', 'comment', 'punct']

  it.each(THEMES)('cada token llega a 4,5:1 sobre el fondo (%s)', (theme) => {
    const bg = tokenIn(theme, 'code-bg')
    for (const token of TOKENS)
      expect(contrast(tokenIn(theme, `code-${token}`), bg), token).toBeGreaterThanOrEqual(AA)
    expect(
      contrast(tokenIn(theme, 'code-var'), tokenIn(theme, 'code-var-bg')),
    ).toBeGreaterThanOrEqual(AA)
  })

  it('la clave y el valor ya no son casi el mismo color: sus tonos se separan', () => {
    for (const theme of THEMES) {
      const gap = Math.abs(hue(tokenIn(theme, 'code-key')) - hue(tokenIn(theme, 'code-value')))
      expect(Math.min(gap, 360 - gap)).toBeGreaterThan(90)
    }
  })
})

/** Tono (0-360) de un `#rrggbb`. */
function hue(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
  const max = Math.max(r, g, b)
  const d = max - Math.min(r, g, b)
  if (d === 0) return 0
  const h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4
  return (h * 60 + 360) % 360
}
