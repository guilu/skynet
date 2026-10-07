import { useEffect, useRef } from 'react'
import * as monaco from 'monaco-editor/editor/editor.api'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'

// Solo el editor base: sin lenguajes ni workers de TypeScript, CSS o HTML. Se carga bajo demanda
// (React.lazy) para que no pese en el resto de la web.
self.MonacoEnvironment = { getWorker: () => new EditorWorker() }

let registered = false

/** Lenguaje `diff` (no viene en el editor base) y temas con sus colores. */
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
  const rules = (dark: boolean): monaco.editor.ITokenThemeRule[] => [
    { token: 'inserted', foreground: dark ? '7ee787' : '116329' },
    { token: 'deleted', foreground: dark ? 'ffa198' : 'a40e26' },
    { token: 'meta', foreground: dark ? '79c0ff' : '0550ae' },
    { token: 'header', foreground: dark ? '8b949e' : '57606a', fontStyle: 'bold' },
    { token: 'key', foreground: dark ? '79c0ff' : '0550ae' },
    { token: 'string', foreground: dark ? 'a5d6ff' : '0a3069' },
    { token: 'number', foreground: dark ? 'ffa657' : '953800' },
    { token: 'keyword', foreground: dark ? 'ff7b72' : 'cf222e' },
  ]
  monaco.editor.defineTheme('skynet-light', {
    base: 'vs',
    inherit: true,
    rules: rules(false),
    colors: {},
  })
  monaco.editor.defineTheme('skynet-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: rules(true),
    colors: {},
  })
}

/** Tema de la web: `data-theme` en `<html>` o, si no hay, el del sistema. */
function isDark(): boolean {
  const theme = document.documentElement.getAttribute('data-theme')
  if (theme) return theme === 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
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
    const instance = monaco.editor.create(container.current!, {
      readOnly: true,
      domReadOnly: true,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      wordWrap: language === 'plaintext' ? 'on' : 'off',
      renderWhitespace: 'none',
      ariaLabel: label,
      theme: isDark() ? 'skynet-dark' : 'skynet-light',
      fontSize: 13,
    })
    editor.current = instance
    const media = window.matchMedia?.('(prefers-color-scheme: dark)')
    const retheme = () => monaco.editor.setTheme(isDark() ? 'skynet-dark' : 'skynet-light')
    const observer = new MutationObserver(retheme)
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
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
