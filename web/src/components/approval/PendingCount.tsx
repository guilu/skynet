import { usePendingApprovals } from './usePendingApprovals'

/** Contador del menú «Aprobaciones»: cuántas esperan una decisión; nada si no hay ninguna. */
export function PendingCount() {
  const pending = usePendingApprovals()
  const n = pending.data?.length ?? 0
  if (n === 0) return null
  return (
    <span className="nav-count">
      <span className="visually-hidden">, </span>
      {n}
      <span className="visually-hidden"> {n === 1 ? 'pendiente' : 'pendientes'}</span>
    </span>
  )
}
