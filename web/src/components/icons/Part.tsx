import type { ReactNode } from 'react'

/** Una forma con su labio debajo y, encima, su detalle; `a` la hace moverse. */
export function Part({ a, ink, children }: { a?: string; ink?: ReactNode; children: ReactNode }) {
  return (
    <g className={a ? `a ${a}` : undefined}>
      <g className="sh">{children}</g>
      <g className="fi">{children}</g>
      {ink}
    </g>
  )
}
