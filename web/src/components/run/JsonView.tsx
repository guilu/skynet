import { useState } from 'react'
import { formatNumber } from '../../format'
import { Button } from '../ui/Button'

/** Cadenas más largas que esto salen recortadas, con un botón para verlas enteras. */
const STRING_PREVIEW = 400

/**
 * Visor de JSON plegable: objetos y listas se abren y cierran por niveles (abiertos los dos
 * primeros) y las cadenas largas salen recortadas. Todo se pinta como texto.
 */
export function JsonView({ value, label }: { value: unknown; label: string }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    await navigator.clipboard?.writeText(JSON.stringify(value, null, 2))
    setCopied(true)
  }
  return (
    <div className="json-view">
      <div className="json-view-head">
        <span className="muted small">{label}</span>
        <Button type="button" variant="link" className="small" onClick={() => void copy()}>
          {copied ? 'Copiado' : 'Copiar JSON'}
        </Button>
      </div>
      <div className="json-tree">
        <Node name={null} value={value} depth={0} />
      </div>
    </div>
  )
}

function Node({ name, value, depth }: { name: string | null; value: unknown; depth: number }) {
  const key = name != null && <span className="json-key">{name}: </span>
  if (value !== null && typeof value === 'object') {
    const entries = Array.isArray(value)
      ? value.map((v, i) => [String(i), v] as const)
      : Object.entries(value as Record<string, unknown>)
    const [open, close] = Array.isArray(value) ? ['[', ']'] : ['{', '}']
    const size = Array.isArray(value)
      ? `${entries.length} ${entries.length === 1 ? 'elemento' : 'elementos'}`
      : `${entries.length} ${entries.length === 1 ? 'clave' : 'claves'}`
    if (entries.length === 0) {
      return (
        <div className="json-leaf">
          {key}
          <span className="json-punct">
            {open}
            {close}
          </span>
        </div>
      )
    }
    return (
      <details open={depth < 2} className="json-branch">
        <summary>
          {key}
          <span className="json-punct">{open}</span>
          <span className="muted small"> {size} </span>
          <span className="json-punct json-close-inline">{close}</span>
        </summary>
        <div className="json-children">
          {entries.map(([k, v]) => (
            <Node key={k} name={k} value={v} depth={depth + 1} />
          ))}
        </div>
        <span className="json-punct">{close}</span>
      </details>
    )
  }
  return (
    <div className="json-leaf">
      {key}
      <Primitive value={value} />
    </div>
  )
}

function Primitive({ value }: { value: unknown }) {
  const [full, setFull] = useState(false)
  if (typeof value === 'string') {
    const long = value.length > STRING_PREVIEW
    return (
      <>
        <span className="json-string">
          “{long && !full ? `${value.slice(0, STRING_PREVIEW)}…` : value}”
        </span>
        {long && (
          <Button variant="link" className="small" onClick={() => setFull(!full)}>
            {full ? 'Ver menos' : `Ver completo (${formatNumber(value.length)} caracteres)`}
          </Button>
        )}
      </>
    )
  }
  const kind = value === null ? 'null' : typeof value
  return (
    <span className={`json-${kind === 'number' || kind === 'boolean' ? kind : 'null'}`}>
      {String(value)}
    </span>
  )
}
