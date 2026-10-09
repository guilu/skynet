import { MapPinOff } from 'lucide-react'
import { Link } from 'react-router'
import { EmptyState } from '../components/list/DataTable'

/** Ruta que no existe: lo dice y ofrece volver al inicio. */
export function NotFound() {
  return (
    <>
      <h1>Página no encontrada</h1>
      <EmptyState icon={MapPinOff} title="Aquí no hay nada.">
        <p>
          Puede que el enlace esté mal copiado o que lo que buscabas ya no exista.{' '}
          <Link to="/">Volver al dashboard</Link>
        </p>
      </EmptyState>
    </>
  )
}
