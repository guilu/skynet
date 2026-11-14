/**
 * Resaltado del YAML de los workflows (WF-D): un tokenizador por líneas que comparten el editor
 * (Monaco) y la lectura en el móvil (un `<pre>`). Distingue las claves, los valores, las
 * referencias a fases y agentes, las variables `{{…}}`, los literales, los comentarios y la
 * puntuación. No es un parser de YAML: solo pinta, y lo que no reconoce queda como valor.
 */

export type YamlTokenType =
  'key' | 'string' | 'ref' | 'variable' | 'literal' | 'comment' | 'delimiter' | 'text'

export interface YamlToken {
  type: YamlTokenType
  /** Columna en la que empieza, desde 0. */
  start: number
  text: string
}

/** Lo que una línea deja abierto para la siguiente. */
export interface YamlState {
  /** Dentro de un bloque `|` o `>`: la sangría de la clave que lo abrió. */
  block: number | null
  /** Dentro de una lista de referencias (`dependsOn:` seguido de `- fase`): su sangría. */
  refList: number | null
}

export const INITIAL_STATE: YamlState = { block: null, refList: null }

/** Claves cuyo valor nombra una fase o un agente de la misma definición. */
export const REF_KEYS = new Set([
  'agent',
  'dependsOn',
  'fix',
  'revise',
  'workspaceFrom',
  'stage',
  'fallback',
])

const KEY = /^("[^"]*"|'[^']*'|[^\s:#'"[\]{},][^:#]*?)(\s*:)(?=\s|$)/
const LITERAL = /^(true|false|null|~|-?\d+(\.\d+)?)$/i
const VARIABLE = /\{\{[^}]*\}\}/g

const indentOf = (line: string) => line.length - line.trimStart().length

/** Un valor suelto: sus `{{…}}` aparte y, si no tiene comillas, el comentario del final. */
function scalar(text: string, start: number, type: 'string' | 'ref', out: YamlToken[]): void {
  let value = text
  let comment = ''
  if (!/^["']/.test(value)) {
    const hash = value.search(/\s#/)
    if (hash >= 0) {
      comment = value.slice(hash)
      value = value.slice(0, hash)
    }
  }
  const trimmed = value.trim()
  if (type === 'string' && LITERAL.test(trimmed)) out.push({ type: 'literal', start, text: value })
  else if (type === 'string') variables(value, start, out)
  else out.push({ type, start, text: value })
  if (comment) out.push({ type: 'comment', start: start + value.length, text: comment })
}

/** Texto con variables `{{…}}`: cada una, su propio token. */
function variables(text: string, start: number, out: YamlToken[], type: YamlTokenType = 'string') {
  let last = 0
  for (const match of text.matchAll(VARIABLE)) {
    if (match.index > last)
      out.push({ type, start: start + last, text: text.slice(last, match.index) })
    out.push({ type: 'variable', start: start + match.index, text: match[0] })
    last = match.index + match[0].length
  }
  if (last < text.length) out.push({ type, start: start + last, text: text.slice(last) })
}

/** `[a, "b?"]`: corchetes y comas como puntuación, cada elemento como referencia o valor. */
function flow(text: string, start: number, type: 'string' | 'ref', out: YamlToken[]) {
  const parts = text.split(/([[\]{},])/)
  let at = start
  for (const part of parts) {
    if (part === '') continue
    if (/^[[\]{},]$/.test(part)) out.push({ type: 'delimiter', start: at, text: part })
    else if (part.trim() === '') out.push({ type: 'text', start: at, text: part })
    else if (part.trimStart().startsWith('#')) out.push({ type: 'comment', start: at, text: part })
    else scalar(part, at, type, out)
    at += part.length
  }
}

/** Los tokens de una línea y el estado con el que empieza la siguiente. */
export function tokenizeLine(
  line: string,
  state: YamlState,
): { tokens: YamlToken[]; state: YamlState } {
  const tokens: YamlToken[] = []
  const indent = indentOf(line)
  const blank = line.trim() === ''

  if (state.block != null && (blank || indent > state.block)) {
    variables(line, 0, tokens)
    return { tokens, state }
  }
  const next: YamlState = { block: null, refList: state.refList }
  if (blank) return { tokens: line ? [{ type: 'text', start: 0, text: line }] : [], state: next }
  if (next.refList != null && indent < next.refList) next.refList = null

  if (indent > 0) tokens.push({ type: 'text', start: 0, text: line.slice(0, indent) })
  let at = indent
  let rest = line.slice(indent)

  if (rest.startsWith('#')) {
    tokens.push({ type: 'comment', start: at, text: rest })
    return { tokens, state: next }
  }

  let inRefList = false
  const dash = /^-(\s+|$)/.exec(rest)
  // Una clave a la altura de `dependsOn:` (o más a la izquierda) cierra su lista.
  if (!dash && next.refList != null && indent <= next.refList) next.refList = null
  if (dash) {
    tokens.push({ type: 'delimiter', start: at, text: '-' })
    if (dash[1]) tokens.push({ type: 'text', start: at + 1, text: dash[1] })
    inRefList = next.refList != null && indent >= next.refList
    at += dash[0].length
    rest = rest.slice(dash[0].length)
    if (!rest) return { tokens, state: next }
  }

  const key = KEY.exec(rest)
  if (!key) {
    if (rest.startsWith('[') || rest.startsWith('{'))
      flow(rest, at, inRefList ? 'ref' : 'string', tokens)
    else scalar(rest, at, inRefList ? 'ref' : 'string', tokens)
    return { tokens, state: next }
  }

  const name = key[1]
  tokens.push({ type: 'key', start: at, text: name })
  tokens.push({ type: 'delimiter', start: at + name.length, text: key[2] })
  at += key[0].length
  rest = rest.slice(key[0].length)
  const isRef = REF_KEYS.has(name.replace(/^["']|["']$/g, ''))
  const space = /^\s*/.exec(rest)![0]
  if (space) tokens.push({ type: 'text', start: at, text: space })
  at += space.length
  rest = rest.slice(space.length)

  if (rest === '') {
    // `dependsOn:` con la lista debajo: sus `- fase` son referencias.
    if (isRef) next.refList = indent
    return { tokens, state: next }
  }
  if (/^[|>][-+0-9]*\s*(#.*)?$/.test(rest)) {
    tokens.push({ type: 'delimiter', start: at, text: rest })
    next.block = dash ? indent + 2 : indent
    return { tokens, state: next }
  }
  if (rest.startsWith('#')) tokens.push({ type: 'comment', start: at, text: rest })
  else if (rest.startsWith('[') || rest.startsWith('{'))
    flow(rest, at, isRef ? 'ref' : 'string', tokens)
  else scalar(rest, at, isRef ? 'ref' : 'string', tokens)
  return { tokens, state: next }
}

/** Todas las líneas de un texto, para pintarlo fuera de Monaco. */
export function tokenize(text: string): YamlToken[][] {
  let state = INITIAL_STATE
  return text.split('\n').map((line) => {
    const result = tokenizeLine(line, state)
    state = result.state
    return result.tokens
  })
}
