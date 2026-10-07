import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'

export function renderAt(path: string, ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * Sustituye fetch por respuestas JSON según la ruta pedida. Una clave puede llevar el método
 * delante (`'POST /api/x'`) para distinguirla de la lectura de la misma ruta.
 */
export function mockFetch(routes: Record<string, unknown>) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString()
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    const key = `${init?.method ?? 'GET'} ${path}`
    const match = key in routes ? key : path in routes ? path : null
    if (match == null) return new Response('{}', { status: 404 })
    const value = routes[match]
    // Una respuesta ya hecha (p. ej. el contenido de un artefacto) se devuelve tal cual.
    if (value instanceof Response) return value.clone()
    return new Response(JSON.stringify(value), { status: 200 })
  }
}
