import { Component, lazy, Suspense, type ReactNode } from 'react'
import { useTouchOnly } from '../../useTouchOnly'

const MonacoView = lazy(() => import('./MonacoView'))

interface Props {
  text: string
  language: 'diff' | 'json' | 'plaintext'
  label: string
  height?: number
}

/**
 * Texto de un artefacto en Monaco, que se descarga la primera vez que hace falta. Mientras llega,
 * si no se puede cargar o en un móvil (donde Monaco no deja seleccionar ni copiar), el mismo texto
 * en un bloque normal.
 */
export function CodeView(props: Props) {
  const touchOnly = useTouchOnly()
  const fallback = (
    // Con el panel estrecho el bloque se desplaza: con tabIndex se puede recorrer con el teclado.
    <pre className="json prewrap code-fallback" aria-label={props.label} tabIndex={0}>
      {props.text}
    </pre>
  )
  if (touchOnly) return fallback
  return (
    <Fallback fallback={fallback}>
      <Suspense fallback={fallback}>
        <MonacoView {...props} />
      </Suspense>
    </Fallback>
  )
}

/** Si Monaco falla al cargar o al arrancar, se queda el texto plano. */
class Fallback extends Component<
  { fallback: ReactNode; children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children
  }
}
