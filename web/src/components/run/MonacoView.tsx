import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor/editor/editor.api'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'

// Solo el editor base: sin lenguajes ni workers de TypeScript, CSS o HTML. Se carga bajo demanda
// (React.lazy) para que no pese en el resto de la web.
self.MonacoEnvironment = { getWorker: () => new EditorWorker() }

let registered = false

/** Lenguajes `diff` y `json`, que no vienen en el editor base. */
function register() {
  if (registered) return
  registered = true
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
function defineTheme() {
  const style = getComputedStyle(document.documentElement)
  const token = (name: string) => toHex(style.getPropertyValue(name).trim()).replace('#', '')
  const c = (name: string) => `#${token(name)}`
  monaco.editor.defineTheme('skynet', {
    base: isDark() ? 'vs-dark' : 'vs',
    inherit: true,
    rules: [
      { token: '', foreground: token('--fg') },
      { token: 'inserted', foreground: token('--ok-ink') },
      { token: 'deleted', foreground: token('--bad-ink') },
      { token: 'meta', foreground: token('--live-ink'), fontStyle: 'bold' },
      { token: 'header', foreground: token('--muted'), fontStyle: 'bold' },
      { token: 'key', foreground: token('--primary-ink') },
      { token: 'string', foreground: token('--ok-ink') },
      { token: 'number', foreground: token('--warn-ink') },
      { token: 'keyword', foreground: token('--live-ink') },
    ],
    colors: {
      'editor.background': c('--surface-2'),
      'editor.foreground': c('--fg'),
      'editorLineNumber.foreground': c('--muted'),
      'editorLineNumber.activeForeground': c('--fg'),
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

interface Props {
  text: string
  language: 'diff' | 'json' | 'plaintext'
  /** Etiqueta accesible del editor. */
  label: string
  height?: number
}

/** Texto en un editor Monaco de solo lectura. */
export default function MonacoView({ text, language, label, height = 420 }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | null>(null)

  useEffect(() => {
    register()
    defineTheme()
    const instance = monaco.editor.create(container.current!, {
      readOnly: true,
      domReadOnly: true,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      wordWrap: language === 'plaintext' ? 'on' : 'off',
      renderWhitespace: 'none',
      ariaLabel: label,
      theme: 'skynet',
      fontSize: 13,
      fontFamily: "'JetBrains Mono', ui-monospace, monospace",
      padding: { top: 10, bottom: 10 },
      renderLineHighlight: 'none',
    })
    editor.current = instance
    const media = window.matchMedia?.('(prefers-color-scheme: dark)')
    const retheme = applyTheme
    const observer = new MutationObserver(retheme)
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'data-palette'],
    })
    media?.addEventListener('change', retheme)
    return () => {
      observer.disconnect()
      media?.removeEventListener('change', retheme)
      instance.getModel()?.dispose()
      instance.dispose()
      editor.current = null
    }
    // El editor se crea una vez; el texto y el lenguaje se cambian en el modelo.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const instance = editor.current
    if (!instance) return
    instance.getModel()?.dispose()
    instance.setModel(monaco.editor.createModel(text, language))
    instance.updateOptions({ wordWrap: language === 'plaintext' ? 'on' : 'off', ariaLabel: label })
  }, [text, language, label])

  return <div ref={container} className="monaco-view" style={{ height }} />
}
