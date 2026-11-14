import { Check, Copy, Download, WrapText } from 'lucide-react'
import { useState } from 'react'
import { cn } from '../../lib/cn'

/** Qué significa cada color del YAML, en el orden en que suelen aparecer. */
const LEGEND = [
  { type: 'key', label: 'Clave' },
  { type: 'string', label: 'Valor' },
  { type: 'ref', label: 'Fase o agente' },
  { type: 'variable', label: 'Variable {{…}}' },
  { type: 'literal', label: 'Número o sí/no' },
  { type: 'comment', label: 'Comentario' },
] as const

/**
 * Barra del YAML (WF-D): la leyenda de colores, ajustar las líneas largas, copiar y descargar.
 */
export function YamlToolbar({
  text,
  fileName,
  wrap,
  onWrapChange,
}: {
  text: string
  fileName: string
  wrap: boolean
  onWrapChange: (wrap: boolean) => void
}) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } catch {
      setCopied(false)
    }
  }
  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/yaml' }))
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.click()
    URL.revokeObjectURL(url)
  }
  return (
    <div className="yaml-toolbar">
      <ul className="yaml-legend" aria-label="Colores del YAML">
        {LEGEND.map((l) => (
          <li key={l.type}>
            <span className={cn('yaml-swatch', `yk-${l.type}`)} aria-hidden="true">
              Aa
            </span>
            {l.label}
          </li>
        ))}
      </ul>
      <div className="yaml-tools">
        <button
          type="button"
          className="chip"
          aria-pressed={wrap}
          onClick={() => onWrapChange(!wrap)}
        >
          <WrapText size={16} strokeWidth={2.25} aria-hidden="true" />
          Ajustar líneas
        </button>
        <button type="button" className="chip" onClick={() => void copy()}>
          {copied ? (
            <Check size={16} strokeWidth={2.5} aria-hidden="true" />
          ) : (
            <Copy size={16} strokeWidth={2.25} aria-hidden="true" />
          )}
          <span aria-live="polite">{copied ? 'Copiado' : 'Copiar'}</span>
        </button>
        <button type="button" className="chip" onClick={download}>
          <Download size={16} strokeWidth={2.25} aria-hidden="true" />
          Descargar
        </button>
      </div>
    </div>
  )
}
