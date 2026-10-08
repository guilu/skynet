import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import runners from '../../fixtures/contracts/runners.json'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const unauthorized = () => new Response('{"detail":"Sin sesión"}', { status: 401 })

describe('login', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('sin sesión pide usuario y contraseña, entra con el token CSRF y sigue en la misma página', async () => {
    let loggedIn = false
    const routes = mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/runners': runners,
      'POST /api/auth/login': { username: 'admin' },
    })
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/auth/session') {
        return loggedIn ? new Response('{"username":"admin"}') : unauthorized()
      }
      if (String(input) === '/api/auth/login') loggedIn = true
      return routes(input, init)
    })
    vi.stubGlobal('fetch', fetch)
    document.cookie = 'XSRF-TOKEN=token-csrf'

    renderAt('/runners', <App />)
    fireEvent.change(await screen.findByLabelText('Usuario'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'secreto' } })
    fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Runners' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cuenta: admin' })).toBeInTheDocument()
    const login = fetch.mock.calls.find(([url]) => url === '/api/auth/login')!
    expect(JSON.parse(login[1]!.body as string)).toEqual({ username: 'admin', password: 'secreto' })
    expect((login[1]!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-csrf')
  })

  it('un login fallido muestra el motivo y no sale del formulario', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/actuator/health': { status: 'UP' },
          '/api/auth/session': unauthorized(),
          'POST /api/auth/login': new Response('{"detail":"Usuario o contraseña incorrectos"}', {
            status: 401,
          }),
        }),
      ),
    )
    renderAt('/', <App />)
    fireEvent.change(await screen.findByLabelText('Usuario'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Contraseña'), { target: { value: 'mal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Usuario o contraseña incorrectos')
    expect(screen.getByLabelText('Usuario')).toBeInTheDocument()
  })

  it('un 401 a mitad de sesión vuelve al login', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/actuator/health': { status: 'UP' },
          '/api/auth/session': { username: 'admin' },
          '/api/runners': unauthorized(),
        }),
      ),
    )
    renderAt('/runners', <App />)
    expect(await screen.findByLabelText('Usuario')).toBeInTheDocument()
  })

  it('salir cierra la sesión y vuelve al login', async () => {
    const fetch = vi.fn(
      mockFetch({
        '/actuator/health': { status: 'UP' },
        '/api/auth/session': { username: 'admin' },
        '/api/runners': runners,
        'POST /api/auth/logout': new Response(null, { status: 204 }),
      }),
    )
    vi.stubGlobal('fetch', fetch)
    renderAt('/runners', <App />)
    fireEvent.keyDown(await screen.findByRole('button', { name: 'Cuenta: admin' }), {
      key: 'Enter',
    })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Salir' }))
    expect(await screen.findByLabelText('Usuario')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      '/api/auth/logout',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('revocar el token de un runner pide confirmación', async () => {
    const fetch = vi.fn(
      mockFetch({
        '/actuator/health': { status: 'UP' },
        '/api/auth/session': { username: 'admin' },
        '/api/runners': runners,
        [`POST /api/runners/${runners[0].id}/revoke`]: new Response(null, { status: 204 }),
      }),
    )
    vi.stubGlobal('fetch', fetch)
    renderAt('/runners', <App />)
    fireEvent.click((await screen.findAllByRole('button', { name: 'Revocar token…' }))[0])
    fireEvent.click(screen.getByRole('button', { name: 'Sí, revocar' }))
    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        `/api/runners/${runners[0].id}/revoke`,
        expect.objectContaining({ method: 'POST' }),
      ),
    )
    expect(await screen.findByText('Token revocado')).toBeInTheDocument()
  })
})
