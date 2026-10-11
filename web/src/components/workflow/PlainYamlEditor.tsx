import { forwardRef, useImperativeHandle, useRef } from 'react'
import { offsetOf } from './textPosition'
import type { YamlEditorHandle } from './YamlEditor'

interface Props {
  value: string
  onChange?: (value: string) => void
  readOnly?: boolean
  label: string
  /** Ctrl/Cmd+S. */
  onSave?: () => void
  height?: number
}

/**
 * El YAML en un `<textarea>` normal, para los dispositivos táctiles: seleccionar, copiar y pegar
 * funcionan como en cualquier campo de texto. No marca los problemas en su línea; la lista de
 * problemas sigue llevando a ellos.
 */
const PlainYamlEditor = forwardRef<YamlEditorHandle, Props>(function PlainYamlEditor(
  { value, onChange, readOnly = false, label, onSave, height = 520 },
  ref,
) {
  const area = useRef<HTMLTextAreaElement>(null)

  useImperativeHandle(ref, () => ({
    reveal(line, column) {
      const textarea = area.current
      if (!textarea) return
      const offset = offsetOf(textarea.value, line, column)
      textarea.focus()
      textarea.setSelectionRange(offset, offset)
      // Centra la línea: el alto de línea sale del estilo calculado.
      const lineHeight = parseFloat(getComputedStyle(textarea).lineHeight) || 20
      textarea.scrollTop = Math.max(0, (line - 1) * lineHeight - textarea.clientHeight / 2)
    },
  }))

  return (
    <textarea
      ref={area}
      className="plain-yaml-editor"
      aria-label={label}
      value={value}
      readOnly={readOnly}
      onChange={(e) => onChange?.(e.target.value)}
      onKeyDown={(e) => {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
          e.preventDefault()
          onSave?.()
        }
      }}
      style={{ height }}
      // El YAML no se corrige: ni mayúsculas, ni autocorrección, ni sugerencias del teclado.
      spellCheck={false}
      autoCapitalize="off"
      autoCorrect="off"
      autoComplete="off"
      wrap="off"
    />
  )
})

export default PlainYamlEditor
