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

/** Sustituye fetch por respuestas JSON según la ruta pedida. */
export function mockFetch(routes: Record<string, unknown>) {
  return async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString()
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    if (!(path in routes)) return new Response('{}', { status: 404 })
    return new Response(JSON.stringify(routes[path]), { status: 200 })
  }
}
