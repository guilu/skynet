import { useEffect, useRef } from 'react'
import { BASE_OPTIONS, defineTheme, followTheme, monaco, register } from '../../lib/monaco'

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
      ...BASE_OPTIONS,
      readOnly: true,
      domReadOnly: true,
      wordWrap: language === 'plaintext' ? 'on' : 'off',
      ariaLabel: label,
      renderLineHighlight: 'none',
    })
    editor.current = instance
    const unfollow = followTheme()
    return () => {
      unfollow()
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
