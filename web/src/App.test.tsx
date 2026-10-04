import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

describe('App', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('muestra el control plane disponible cuando /actuator/health responde UP', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP' }), { status: 200 })),
    )
    render(<App />)
    expect(await screen.findByText('disponible')).toBeInTheDocument()
  })

  it('muestra el control plane no disponible si la petición falla', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
    render(<App />)
    expect(await screen.findByText('no disponible')).toBeInTheDocument()
  })
})
