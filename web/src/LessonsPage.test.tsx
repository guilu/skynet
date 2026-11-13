import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Lesson, Repository } from './api'
import App from './App'
import { RepositoryLessons } from './components/project/RepositoryLessons'
import { mockFetch, renderAt } from './test/render'

const proposed: Lesson = {
  id: 'l-1',
  projectId: 'p1',
  projectName: 'Demo',
  repositoryId: 'r1',
  repositoryName: 'demo-repo',
  category: 'caso-limite',
  kind: 'prevenir',
  status: 'propuesta',
  suggestedText: 'En este repositorio, los jueces han tenido que pedir en 2 ejecuciones',
  appliedText: null,
  runs: 2,
  findings: 2,
  observationId: null,
  decidedBy: null,
  decidedAt: null,
  reason: null,
  createdAt: '2026-11-13T10:00:00Z',
  updatedAt: '2026-11-13T10:00:00Z',
}

const applied: Lesson = {
  ...proposed,
  id: 'l-2',
  kind: 'no-senalar',
  category: 'estilo',
  status: 'aplicada',
  appliedText: 'No señales los nombres cortos.',
  decidedBy: 'diego',
  decidedAt: '2026-11-13T11:00:00Z',
}

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

/** El cuerpo del `POST` a `path`. */
function body(fetch: ReturnType<typeof stubApi>, path: string): unknown {
  const call = fetch.mock.calls.find(
    ([url, init]) => String(url).endsWith(path) && init?.method === 'POST',
  )
  return call ? JSON.parse(String(call[1]?.body)) : undefined
}

afterEach(() => vi.unstubAllGlobals())

describe('Fábrica › Lecciones (AP-C)', () => {
  it('aplica una propuesta con el texto editado', async () => {
    const fetch = stubApi({
      '/api/factory/lessons?status=propuesta': [proposed],
      'POST /api/lessons/l-1/apply': { ...proposed, status: 'aplicada' },
    })
    renderAt('/lessons', <App />)
    const card = await screen.findByRole('article', {
      name: 'Prevenir: Caso límite en demo-repo',
    })
    expect(screen.getByRole('region', { name: 'Demo › demo-repo' })).toContainElement(card)
    expect(card).toHaveTextContent('en 2 ejecuciones · 2 hallazgos')
    const text = within(card).getByLabelText('Texto de la lección')
    fireEvent.change(text, { target: { value: 'Prueba siempre la lista vacía' } })
    fireEvent.click(within(card).getByRole('button', { name: 'Aplicar en la memoria' }))
    await waitFor(() =>
      expect(body(fetch, '/api/lessons/l-1/apply')).toEqual({
        text: 'Prueba siempre la lista vacía',
      }),
    )
  })

  it('descartar pide un motivo', async () => {
    const fetch = stubApi({
      '/api/factory/lessons?status=propuesta': [proposed],
      'POST /api/lessons/l-1/dismiss': { ...proposed, status: 'descartada' },
    })
    renderAt('/lessons', <App />)
    const card = await screen.findByRole('article', {
      name: 'Prevenir: Caso límite en demo-repo',
    })
    fireEvent.click(within(card).getByRole('button', { name: 'Descartar' }))
    const group = within(card).getByRole('group', { name: 'Descartar la lección' })
    const confirm = within(group).getByRole('button', { name: 'Descartar' })
    expect(confirm).toBeDisabled()
    fireEvent.change(within(group).getByLabelText('Motivo'), {
      target: { value: 'Sí queremos verlo' },
    })
    fireEvent.click(confirm)
    await waitFor(() =>
      expect(body(fetch, '/api/lessons/l-1/dismiss')).toEqual({ reason: 'Sí queremos verlo' }),
    )
  })

  it('enseña las aplicadas con quién las aplicó y deja copiarlas para REVIEW.md', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.assign(navigator, { clipboard: { writeText } })
    stubApi({ '/api/factory/lessons?status=aplicada': [applied] })
    renderAt('/lessons?status=aplicada', <App />)
    const card = await screen.findByRole('article', {
      name: 'No señalar: Estilo en demo-repo',
    })
    expect(screen.getByRole('tab', { name: 'Aplicadas' })).toHaveAttribute('aria-selected', 'true')
    expect(card).toHaveTextContent('No señales los nombres cortos.')
    expect(card).toHaveTextContent('Aplicada por diego')
    expect(within(card).getByRole('button', { name: 'Desactivar' })).toBeInTheDocument()
    fireEvent.click(within(card).getByRole('button', { name: 'Copiar para REVIEW.md' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('No señales los nombres cortos.'))
  })

  it('sin lecciones, lo dice', async () => {
    stubApi({ '/api/factory/lessons?status=propuesta': [] })
    renderAt('/lessons', <App />)
    expect(await screen.findByText('Ninguna lección propuesta.')).toBeInTheDocument()
  })
})

describe('Lecciones del repositorio (AP-C)', () => {
  const repository = { id: 'r1', projectId: 'p1', name: 'demo-repo' } as Repository

  it('enseña las aplicadas de ese repositorio y deja desactivarlas con un motivo', async () => {
    const other = { ...applied, id: 'l-3', repositoryId: 'r2', repositoryName: 'otro' }
    const fetch = stubApi({
      '/api/projects/p1/lessons?status=aplicada': [applied, other],
      'POST /api/lessons/l-2/dismiss': { ...applied, status: 'descartada' },
    })
    renderAt('/projects/p1', <RepositoryLessons repository={repository} />)
    const section = await screen.findByRole('region', { name: 'Lecciones de demo-repo' })
    const card = await within(section).findByRole('article', {
      name: 'No señalar: Estilo en demo-repo',
    })
    expect(within(section).getAllByRole('article')).toHaveLength(1)
    fireEvent.click(within(card).getByRole('button', { name: 'Desactivar' }))
    const group = within(card).getByRole('group', { name: 'Desactivar la lección' })
    fireEvent.change(within(group).getByLabelText('Motivo'), { target: { value: 'Ya no aplica' } })
    fireEvent.click(within(group).getByRole('button', { name: 'Desactivar' }))
    await waitFor(() =>
      expect(body(fetch, '/api/lessons/l-2/dismiss')).toEqual({ reason: 'Ya no aplica' }),
    )
  })

  it('sin lecciones aplicadas, lo dice', async () => {
    stubApi({ '/api/projects/p1/lessons?status=aplicada': [] })
    renderAt('/projects/p1', <RepositoryLessons repository={repository} />)
    expect(await screen.findByText(/Ninguna: lo que se repite/)).toBeInTheDocument()
  })
})
