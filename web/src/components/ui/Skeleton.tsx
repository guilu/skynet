import type { CSSProperties } from 'react'
import { cn } from '../../lib/cn'

/** Bloque gris con brillo que ocupa el sitio de algo que aún no ha llegado. */
export function Skeleton({ className, style }: { className?: string; style?: CSSProperties }) {
  return <span className={cn('skeleton', className)} style={style} aria-hidden="true" />
}

/**
 * Lo que se está cargando, para lectores de pantalla: el esqueleto es decorativo y esto lo
 * anuncia una vez.
 */
function Loading({ label }: { label: string }) {
  return (
    <span role="status" className="visually-hidden">
      Cargando {label}…
    </span>
  )
}

/** Esqueleto de una tabla: cabecera y unas filas con el mismo aspecto que `DataTable`. */
export function TableSkeleton({
  label,
  rows = 5,
  columns = 4,
}: {
  label: string
  rows?: number
  columns?: number
}) {
  return (
    <div className="skeleton-table">
      <Loading label={label} />
      <div className="skeleton-row skeleton-head">
        {Array.from({ length: columns }, (_, c) => (
          <Skeleton key={c} className="skeleton-text" style={{ width: c === 0 ? '40%' : '55%' }} />
        ))}
      </div>
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="skeleton-row">
          {Array.from({ length: columns }, (_, c) => (
            <Skeleton
              key={c}
              className={c === 1 ? 'skeleton-pill' : 'skeleton-text'}
              style={c === 0 ? { width: `${70 - ((r * 13) % 30)}%` } : undefined}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

/** Esqueleto de tarjetas en rejilla (repositorios, workflows). */
export function CardsSkeleton({ label, count = 2 }: { label: string; count?: number }) {
  return (
    <div className="repo-grid">
      <Loading label={label} />
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="card skeleton-card">
          <div className="skeleton-card-head">
            <Skeleton className="skeleton-icon" />
            <span className="skeleton-lines">
              <Skeleton className="skeleton-text" style={{ width: '45%' }} />
              <Skeleton className="skeleton-text" style={{ width: '75%' }} />
            </span>
          </div>
          <Skeleton className="skeleton-text" style={{ width: '90%' }} />
          <Skeleton className="skeleton-text" style={{ width: '60%' }} />
        </div>
      ))}
    </div>
  )
}

/** Esqueleto de las cifras del dashboard. */
export function TilesSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div className="kpis">
      <Loading label="las métricas" />
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="kpi skeleton-kpi">
          <Skeleton className="skeleton-icon" />
          <Skeleton className="skeleton-text" style={{ width: '60%' }} />
          <Skeleton className="skeleton-number" />
        </div>
      ))}
    </div>
  )
}

/** Esqueleto de la vista de una ejecución: cabecera con cifras y los tres paneles. */
export function RunSkeleton() {
  return (
    <div className="skeleton-run">
      <Loading label="la ejecución" />
      <div className="card skeleton-run-head">
        <Skeleton className="skeleton-title" />
        <div className="skeleton-facts">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="skeleton-fact" />
          ))}
        </div>
      </div>
      <div className="card skeleton-panels">
        {[26, 40, 34].map((w, i) => (
          <div key={i} className="skeleton-panel" style={{ flexBasis: `${w}%` }}>
            <Skeleton className="skeleton-text" style={{ width: '50%' }} />
            {Array.from({ length: 5 }, (_, r) => (
              <Skeleton key={r} className="skeleton-line" style={{ width: `${90 - r * 9}%` }} />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}

/** Unas líneas sueltas, para pestañas y paneles pequeños. */
export function LinesSkeleton({ label, lines = 4 }: { label: string; lines?: number }) {
  return (
    <div className="skeleton-lines-block">
      <Loading label={label} />
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} className="skeleton-line" style={{ width: `${92 - i * 14}%` }} />
      ))}
    </div>
  )
}
