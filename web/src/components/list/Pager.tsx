import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '../ui/Button'

/** Paginación de una lista: anterior, posición y siguiente. */
export function Pager({
  page,
  pageSize,
  total,
  noun,
  onPage,
}: {
  page: number
  pageSize: number
  total: number
  noun: string
  onPage: (page: number) => void
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize))
  if (total <= pageSize) return null
  return (
    <nav className="pager" aria-label="Paginación">
      <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        <ChevronLeft size={16} strokeWidth={3} aria-hidden="true" />
        Anterior
      </Button>
      <span>
        Página {page + 1} de {pages} · {total} {noun}
      </span>
      <Button
        variant="secondary"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onPage(page + 1)}
      >
        Siguiente
        <ChevronRight size={16} strokeWidth={3} aria-hidden="true" />
      </Button>
    </nav>
  )
}
