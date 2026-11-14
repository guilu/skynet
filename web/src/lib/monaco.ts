import * as monaco from 'monaco-editor/editor/editor.api'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
import YamlWorker from 'monaco-yaml/yaml.worker?worker'
import { INITIAL_STATE, tokenizeLine, type YamlState } from './yamlTokens'

// Configuración común de los editores Monaco de la web: solo el editor base, sin los lenguajes ni
// los workers de TypeScript, CSS o HTML. Este módulo y quien lo importa se cargan bajo demanda
// (React.lazy) para que no pesen en el resto de la web. El worker de YAML lo usa el editor de
// workflows (monaco-yaml).
self.MonacoEnvironment = {
  getWorker: (_id, label) => (label === 'yaml' ? new YamlWorker() : new EditorWorker()),
}

export { monaco }

/** El estado del tokenizador de YAML como lo pide Monaco. */
class LineState implements monaco.languages.IState {
  readonly value: YamlState
  constructor(value: YamlState) {
    this.value = value
  }
  clone() {
    return new LineState({ ...this.value })
  }
  equals(other: monaco.languages.IState) {
    return (
      other instanceof LineState &&
      other.value.block === this.value.block &&
      other.value.refList === this.value.refList
    )
  }
}

/**
 * YAML con el tokenizador de la web (WF-D) en lugar del Monarch genérico: así las referencias a
 * fases y agentes y las variables `{{…}}` tienen su color, igual que en la lectura del móvil.
 */
function registerYaml() {
  monaco.languages.register({ id: 'yaml', extensions: ['.yaml', '.yml'], aliases: ['YAML'] })
  monaco.languages.setLanguageConfiguration('yaml', {
    comments: { lineComment: '#' },
    brackets: [
      ['{', '}'],
      ['[', ']'],
    ],
    autoClosingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '"', close: '"' },
      { open: "'", close: "'" },
    ],
    folding: { offSide: true },
    onEnterRules: [
      { beforeText: /:\s*$/, action: { indentAction: monaco.languages.IndentAction.Indent } },
    ],
  })
  monaco.languages.setTokensProvider('yaml', {
    getInitialState: () => new LineState(INITIAL_STATE),
    tokenize(line, state) {
      const result = tokenizeLine(line, (state as LineState).value)
      return {
        tokens: result.tokens.map((t) => ({ startIndex: t.start, scopes: `code-${t.type}` })),
        endState: new LineState(result.state),
      }
    },
  })
}

let registered = false

/** Lenguajes `diff` y `json`, que no vienen en el editor base. */
export function register() {
  if (registered) return
  registered = true
  registerYaml()
  monaco.languages.register({ id: 'diff' })
  monaco.languages.setMonarchTokensProvider('diff', {
    tokenizer: {
      root: [
        [/^(diff --git|index|similarity|rename|new file|deleted file|Binary).*$/, 'header'],
        [/^(\+\+\+|---) .*$/, 'header'],
        [/^@@.*$/, 'meta'],
        [/^\+.*$/, 'inserted'],
        [/^-.*$/, 'deleted'],
      ],
    },
  })
  monaco.languages.register({ id: 'json' })
  monaco.languages.setMonarchTokensProvider('json', {
    tokenizer: {
      root: [
        [/"(?:[^"\\]|\\.)*"(?=\s*:)/, 'key'],
        [/"(?:[^"\\]|\\.)*"/, 'string'],
        [/-?\d+(\.\d+)?([eE][+-]?\d+)?/, 'number'],
        [/\b(true|false|null)\b/, 'keyword'],
      ],
    },
  })
}

/** Tema de la web: `data-theme` en `<html>` o, si no hay, el del sistema. */
function isDark(): boolean {
  const theme = document.documentElement.getAttribute('data-theme')
  if (theme) return theme === 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

let probe: CanvasRenderingContext2D | null | undefined

/** Un color CSS cualquiera como `#rrggbb`, que es lo que entiende Monaco. */
function toHex(color: string): string {
  if (/^#[0-9a-f]{6}$/i.test(color)) return color
  probe ??= document.createElement('canvas').getContext('2d')
  if (!probe) return color
  probe.fillStyle = '#000000'
  probe.fillStyle = color
  return probe.fillStyle
}

/**
 * Tema `skynet` con los colores de la web, leídos de sus tokens CSS: así el editor sigue al tema
 * claro u oscuro y, más adelante, a una paleta personalizada.
 */
export function defineTheme() {
  const style = getComputedStyle(document.documentElement)
  const token = (name: string) => toHex(style.getPropertyValue(name).trim()).replace('#', '')
  const c = (name: string) => `#${token(name)}`
  monaco.editor.defineTheme('skynet', {
    base: isDark() ? 'vs-dark' : 'vs',
    inherit: true,
    rules: [
      { token: '', foreground: token('--code-fg') },
      { token: 'inserted', foreground: token('--ok-ink') },
      { token: 'deleted', foreground: token('--bad-ink') },
      { token: 'meta', foreground: token('--live-ink'), fontStyle: 'bold' },
      { token: 'header', foreground: token('--muted'), fontStyle: 'bold' },
      // JSON.
      { token: 'key', foreground: token('--code-key') },
      { token: 'string', foreground: token('--code-value') },
      { token: 'number', foreground: token('--code-literal') },
      { token: 'keyword', foreground: token('--code-literal') },
      // YAML (lib/yamlTokens, WF-D). Prefijo propio para que no lo pisen las reglas de vs.
      { token: 'code-text', foreground: token('--code-fg') },
      { token: 'code-key', foreground: token('--code-key') },
      { token: 'code-string', foreground: token('--code-value') },
      { token: 'code-ref', foreground: token('--code-ref'), fontStyle: 'underline' },
      { token: 'code-variable', foreground: token('--code-var') },
      { token: 'code-literal', foreground: token('--code-literal') },
      { token: 'code-comment', foreground: token('--code-comment'), fontStyle: 'italic' },
      { token: 'code-delimiter', foreground: token('--code-punct') },
    ],
    colors: {
      'editor.background': c('--code-bg'),
      'editor.foreground': c('--code-fg'),
      'editorLineNumber.foreground': c('--code-line'),
      'editorLineNumber.activeForeground': c('--code-fg'),
      'editor.lineHighlightBackground': c('--surface'),
      'editor.lineHighlightBorder': c('--surface'),
      'editor.selectionBackground': c('--primary-soft'),
      'editor.inactiveSelectionBackground': c('--primary-soft'),
      'editorCursor.foreground': c('--primary'),
      'editorWidget.background': c('--surface'),
      'editorWidget.border': c('--border'),
      'scrollbarSlider.background': `${c('--border-strong')}99`,
      'scrollbarSlider.hoverBackground': c('--border-strong'),
      'scrollbarSlider.activeBackground': c('--idle'),
    },
  })
}

function applyTheme() {
  defineTheme()
  monaco.editor.setTheme('skynet')
}

/** Sigue al tema de la web (atributos de `<html>` y el del sistema); devuelve cómo dejar de seguirlo. */
export function followTheme(): () => void {
  const media = window.matchMedia?.('(prefers-color-scheme: dark)')
  const observer = new MutationObserver(applyTheme)
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme', 'data-palette'],
  })
  media?.addEventListener('change', applyTheme)
  return () => {
    observer.disconnect()
    media?.removeEventListener('change', applyTheme)
  }
}

/** Opciones comunes de los editores. */
export const BASE_OPTIONS: monaco.editor.IStandaloneEditorConstructionOptions = {
  automaticLayout: true,
  minimap: { enabled: false },
  scrollBeyondLastLine: false,
  renderWhitespace: 'none',
  theme: 'skynet',
  fontSize: 13,
  fontFamily: "'JetBrains Mono', ui-monospace, monospace",
  padding: { top: 10, bottom: 10 },
  // Sin el resaltado de ocurrencias ni de la selección repetida: se confundían con la selección.
  occurrencesHighlight: 'off',
  selectionHighlight: false,
  // Los corchetes de colores competían con los colores del YAML (las variables `{{…}}`).
  bracketPairColorization: { enabled: false },
}
