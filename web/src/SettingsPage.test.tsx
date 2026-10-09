import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { mockFetch, renderAt } from './test/render'

const NONE = { primary: null, ok: null, warn: null, bad: null, live: null, idle: null }

function stubApi(routes: Record<string, unknown> = {}) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/settings/appearance': { colors: NONE, updatedAt: null },
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

function paletteStyle() {
  return document.getElementById('skynet-palette')?.textContent ?? ''
}

describe('Ajustes > Apariencia', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.getElementById('skynet-palette')?.remove()
    document.documentElement.removeAttribute('data-palette')
    localStorage.clear()
  })

  it('elige una paleta, la previsualiza y al guardarla la aplica a toda la web', async () => {
    const fetch = stubApi({
      'PUT /api/settings/appearance': {
        colors: { ...NONE, primary: '#e8590c' },
        updatedAt: '2026-10-09T12:00:00Z',
      },
    })
    renderAt('/settings', <App />)

    expect(await screen.findByRole('radio', { name: 'Skynet' })).toBeChecked()
    const save = screen.getByRole('button', { name: 'Guardar' })
    expect(save).toBeDisabled()

    fireEvent.click(screen.getByRole('radio', { name: 'Mandarina' }))
    expect(screen.getByLabelText('Acento')).toHaveValue('#e8590c')
    expect(screen.getByText('Cambios sin guardar.')).toBeInTheDocument()
    const preview = screen.getByRole('figure', { name: 'Vista previa en tema claro' })
    expect(preview.style.getPropertyValue('--primary')).toBe('#e8590c')
    // Sobre naranja, el texto del botón pasa a oscuro para llegar a AA.
    expect(preview.style.getPropertyValue('--on-primary')).toBe('#0b1022')
    expect(paletteStyle()).toBe('')

    fireEvent.click(save)
    expect(await screen.findByText('Guardado.')).toHaveAttribute('role', 'status')
    const put = fetch.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(put[1]!.body as string)).toEqual({
      colors: { ...NONE, primary: '#e8590c' },
    })
    await waitFor(() => expect(paletteStyle()).toContain('--primary: #e8590c;'))
    expect(document.documentElement).toHaveAttribute('data-palette')
    expect(JSON.parse(localStorage.getItem('skynet.palette')!)).toEqual({ primary: '#e8590c' })
  })

  it('cambia un color suelto y vuelve a los de Skynet', async () => {
    stubApi({
      '/api/settings/appearance': {
        colors: { ...NONE, bad: '#c2255c' },
        updatedAt: '2026-10-09T12:00:00Z',
      },
    })
    renderAt('/settings', <App />)

    // La paleta guardada se aplica al cargar, también fuera de Ajustes.
    await waitFor(() => expect(paletteStyle()).toContain('--bad: #c2255c;'))
    const ok = await screen.findByLabelText('Correcto')
    expect(screen.getByRole('radio', { name: 'Skynet' })).not.toBeChecked()

    fireEvent.change(ok, { target: { value: '#0e7c86' } })
    const colors = screen.getByRole('region', { name: 'Colores' })
    expect(within(colors).getByText('#0e7c86')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Volver al color de Skynet en Correcto' }))
    expect(within(colors).getByText('#22a45a')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Volver a los colores de Skynet' }))
    expect(screen.getByRole('radio', { name: 'Skynet' })).toBeChecked()
    expect(screen.getByRole('button', { name: 'Guardar' })).toBeEnabled()
  })

  it('una paleta cacheada en el navegador se pinta antes de que responda el servidor', async () => {
    const { applyCachedPalette } = await import('./appearance')
    localStorage.setItem('skynet.palette', JSON.stringify({ live: '#2f9bd6' }))
    applyCachedPalette()
    expect(paletteStyle()).toContain('--live: #2f9bd6;')
  })
})
