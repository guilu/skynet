import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { WorkItem } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const workItem: WorkItem = {
  id: 'w1',
  projectId: 'p1',
  key: 'TKM-1',
  title: 'Arreglar la suma',
  description: null,
  type: 'BUG',
  externalRef: null,
  status: 'OPEN',
  createdAt: '2026-10-08T10:00:00Z',
}

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/projects/p1': { id: 'p1', key: 'TKM', name: 'TokenMeter', description: null },
      '/api/projects/p1/repositories': [],
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

describe('Página del proyecto', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña los trabajos y, en la otra pestaña, los repositorios', async () => {
    stubApi({ '/api/projects/p1/work-items': [workItem] })
    renderAt('/projects/p1', <App />)

    const table = await screen.findByRole('table', { name: 'Trabajos' })
    expect(within(table).getByRole('link', { name: 'TKM-1 Arreglar la suma' })).toHaveAttribute(
      'href',
      '/work-items/w1',
    )
    expect(within(table).getByText('Bug')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: /Repositorios/ }))
    expect(screen.getByRole('tab', { name: /Repositorios/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(await screen.findByText('Ningún repositorio registrado.')).toBeInTheDocument()
  })

  it('crea un trabajo en el panel lateral, que se cierra al terminar', async () => {
    const fetch = stubApi({
      '/api/projects/p1/work-items': [],
      'POST /api/projects/p1/work-items': workItem,
    })
    renderAt('/projects/p1', <App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Nuevo trabajo' }))
    const sheet = screen.getByRole('dialog', { name: 'Nuevo trabajo' })
    fireEvent.change(within(sheet).getByLabelText('Título'), {
      target: { value: 'Arreglar la suma' },
    })
    fireEvent.change(within(sheet).getByLabelText('Tipo'), { target: { value: 'BUG' } })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Crear trabajo' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const post = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ title: 'Arreglar la suma', type: 'BUG' })
  })

  it('el panel lateral se cierra con Escape sin enviar nada', async () => {
    const fetch = stubApi({ '/api/projects/p1/work-items': [] })
    renderAt('/projects/p1', <App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Nuevo repositorio' }))
    const sheet = screen.getByRole('dialog', { name: 'Registrar repositorio' })
    expect(sheet).toHaveAccessibleDescription(/clonado en la máquina del runner/)
    fireEvent.keyDown(sheet, { key: 'Escape' })

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
})

describe('Carga y rutas desconocidas', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('mientras llegan los datos enseña un esqueleto que se anuncia', async () => {
    // Los proyectos no llegan nunca: la lista se queda cargando.
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) =>
        String(input).endsWith('/api/projects')
          ? new Promise<Response>(() => {})
          : mockFetch({ '/api/auth/session': { username: 'admin' } })(input),
      ),
    )
    renderAt('/projects', <App />)
    expect(await screen.findByText('Cargando los proyectos…')).toHaveAttribute('role', 'status')
  })

  it('una ruta que no existe lo dice y enlaza al dashboard', async () => {
    stubApi({})
    renderAt('/no-existe', <App />)
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Página no encontrada' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver al dashboard' })).toHaveAttribute('href', '/')
  })
})
