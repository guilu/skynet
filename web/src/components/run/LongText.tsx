import { useState } from 'react'
import { formatNumber } from '../../format'

/** Texto más largo que esto se muestra recortado, con un botón para verlo entero. */
export const PREVIEW_CHARS = 2000

/**
 * Texto en un bloque que respeta saltos de línea (monoespaciado si es código o JSON); si es muy
 * largo, recortado con «Ver completo».
 */
export function LongText({ text, code = false }: { text: string; code?: boolean }) {
  const [full, setFull] = useState(false)
  const long = text.length > PREVIEW_CHARS
  return (
    <>
      <pre className={code ? 'json prewrap' : 'text-block prewrap'}>
        {long && !full ? `${text.slice(0, PREVIEW_CHARS)}…` : text}
      </pre>
      {long && (
        <button type="button" className="link small" onClick={() => setFull(!full)}>
          {full ? 'Ver menos' : `Ver completo (${formatNumber(text.length)} caracteres)`}
        </button>
      )}
    </>
  )
}
