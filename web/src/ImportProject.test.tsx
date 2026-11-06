import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import catalog from '../../fixtures/contracts/repository-catalog.json'
import { ImportProjectPage } from './pages/ImportProjectPage'
import { mockFetch, renderAt } from './test/render'

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(mockFetch(routes))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

afterEach(() => vi.unstubAllGlobals())

describe('Importar proyecto', () => {
  it('sin catálogo explica qué configurar en el runner', async () => {
    stubApi({ '/api/repository-catalog': [] })
    renderAt('/projects/import', <ImportProjectPage />)
    expect(await screen.findByText('El runner todavía no busca repositorios')).toBeInTheDocument()
    expect(screen.getAllByText(/SKYNET_REPO_ROOTS/).length).toBeGreaterThan(0)
  })

  it('lo ya registrado no se puede elegir; lo demás llega relleno hasta «Crear proyecto»', async () => {
    const fetch = stubApi({
      '/api/repository-catalog': catalog,
      '/api/projects/key-suggestion?name=Forma': { key: 'FORMA' },
      'POST /api/projects/import': {
        project: { id: 'p-forma', key: 'FORMA', name: 'Forma' },
        repository: { id: 'r-forma' },
      },
    })
    renderAt('/projects/import', <ImportProjectPage />)

    const akademia = await screen.findByRole('radio', { name: /akademia/ })
    expect(akademia).toBeDisabled()
    expect(screen.getByRole('link', { name: 'AKA' })).toHaveAttribute(
      'href',
      '/projects/0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a72',
    )
    const forma = screen.getByRole('radio', { name: /forma/ })
    expect(forma).toBeEnabled()
    expect(screen.getByText('Cambios sin commitear')).toBeInTheDocument()

    fireEvent.click(forma)
    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: /^Clave/ })).toHaveValue('FORMA'),
    )
    expect(screen.getByRole('textbox', { name: /^Nombre/ })).toHaveValue('Forma')
    expect(screen.getByRole('textbox', { name: /^Descripción/ })).toHaveValue(
      'Gestor de entrenamientos de fuerza',
    )
    // GitHub dice otra cosa: se ofrece, no se impone.
    fireEvent.click(screen.getByRole('button', { name: 'Usar esa' }))
    expect(screen.getByRole('textbox', { name: /^Descripción/ })).toHaveValue(
      'Forma: entrenamientos',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    expect(screen.getByRole('textbox', { name: /^Comando de validación/ })).toHaveValue('pnpm test')
    expect(screen.getByRole('textbox', { name: /^Ruta en el runner/ })).toHaveAttribute('readonly')

    fireEvent.click(screen.getByRole('button', { name: 'Siguiente' }))
    expect(screen.getByRole('checkbox', { name: /Conocer el repositorio/ })).not.toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Crear proyecto' }))

    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith('/api/projects/import', expect.anything()),
    )
    const call = fetch.mock.calls.find(([url]) => url === '/api/projects/import')!
    const body = JSON.parse(String(call[1]?.body))
    expect(body).toMatchObject({
      key: 'FORMA',
      name: 'Forma',
      description: 'Forma: entrenamientos',
      repository: {
        localPath: '/home/diego/code/forma',
        remoteUrl: 'git@github.com:guilu/forma.git',
        defaultBranch: 'main',
        validationCommand: 'pnpm test',
        protectedPaths: ['.github/workflows/**'],
      },
    })
  })
})
