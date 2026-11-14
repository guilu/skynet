import { forwardRef, useImperativeHandle, useMemo, useRef } from 'react'
import { tokenize } from '../../lib/yamlTokens'
import { cn } from '../../lib/cn'
import type { YamlEditorHandle } from './YamlEditor'

/**
 * El YAML de solo lectura en un `<pre>`, con el mismo resaltado que el editor (WF-D) y sin Monaco:
 * es lo que se ve en el móvil. Se selecciona y se copia como cualquier texto.
 */
const YamlView = forwardRef<YamlEditorHandle, { value: string; label: string; wrap: boolean }>(
  function YamlView({ value, label, wrap }, ref) {
    const lines = useMemo(() => tokenize(value), [value])
    const pre = useRef<HTMLPreElement>(null)
    useImperativeHandle(ref, () => ({
      reveal(line) {
        const target = pre.current?.querySelector<HTMLElement>(`[data-line="${line}"]`)
        target?.scrollIntoView({ block: 'center' })
        target?.focus()
      },
    }))
    return (
      <pre
        ref={pre}
        className={cn('yaml-view', wrap && 'yaml-view-wrap')}
        aria-label={label}
        tabIndex={0}
      >
        <code>
          {lines.map((tokens, i) => (
            <span key={i} className="yaml-line" data-line={i + 1} tabIndex={-1}>
              {tokens.map((t) => (
                <span key={t.start} className={`yk-${t.type}`}>
                  {t.text}
                </span>
              ))}
              {'\n'}
            </span>
          ))}
        </code>
      </pre>
    )
  },
)

export default YamlView
