import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { forwardRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import workflowVersion from '../../fixtures/contracts/workflow-version.json'
import workflow from '../../fixtures/contracts/workflow.json'
import workflows from '../../fixtures/contracts/workflows.json'
import type { DefinitionDetail, ValidationView } from './api'
import App from './App'
import { mockFetch, renderAt } from './test/render'

// Monaco no funciona en jsdom: el editor se sustituye por un textarea con la misma interfaz.
vi.mock('./components/workflow/YamlEditor', () => ({
  default: forwardRef<
    unknown,
    { value: string; onChange?: (v: string) => void; readOnly?: boolean; label: string }
  >(function YamlEditor({ value, onChange, readOnly, label }, _ref) {
    return (
      <textarea
        aria-label={label}
        value={value}
        readOnly={readOnly}
        onChange={(e) => onChange?.(e.target.value)}
      />
    )
  }),
}))

const draft = workflowVersion as DefinitionDetail
const [v2, v1] = workflow.versions
const published: DefinitionDetail = {
  ...draft,
  id: v1.id,
  version: 1,
  status: 'PUBLISHED',
  publishedAt: v1.publishedAt,
}

function stubApi(routes: Record<string, unknown>) {
  const fetch = vi.fn(
    mockFetch({
      '/actuator/health': { status: 'UP' },
      '/api/auth/session': { username: 'admin' },
      '/api/workflows/schema': {},
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const bodies = (fetch: ReturnType<typeof vi.fn>, method: string, path: string) =>
  fetch.mock.calls
    .filter(([url, init]) => String(url).endsWith(path) && (init as RequestInit)?.method === method)
    .map(([, init]) => JSON.parse(String((init as RequestInit).body)))

describe('Workflows', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('la lista enseña la versión publicada y el borrador', async () => {
    stubApi({ '/api/workflows': workflows })
    renderAt('/workflows', <App />)

    const link = await screen.findByRole('link', { name: /revisar-y-corregir/ })
    const row = link.closest('tr')!
    expect(within(row).getByText('v1 · Publicada')).toBeInTheDocument()
    expect(within(row).getByText('v2 · Borrador validado')).toBeInTheDocument()
  })

  it('un borrador con algo que aún no se ejecuta se guarda pero no se publica', async () => {
    const live: ValidationView = {
      key: draft.key,
      validation: { valid: false, publishable: false, problems: [] },
      definition: null,
    }
    live.validation.problems = [
      { severity: 'ERROR', path: 'colr', line: 40, column: 1, message: 'Clave `colr` desconocida' },
    ]
    const fetch = stubApi({
      [`/api/workflows/${draft.key}`]: workflow,
      [`/api/workflow-versions/${draft.id}`]: draft,
      [`/api/workflow-versions/${v1.id}`]: published,
      'POST /api/workflows/validate': live,
      [`PUT /api/workflow-versions/${draft.id}`]: { ...draft, revision: 4 },
    })
    renderAt(`/workflows/${draft.key}`, <App />)

    const editor = await screen.findByLabelText(`YAML de ${draft.key} v2`)
    expect(await screen.findByText('1 aún no se ejecuta')).toBeInTheDocument()
    expect(screen.getByText(/El tipo `verification` todavía no se ejecuta/)).toBeInTheDocument()
    expect(screen.getByText(/Usa algo que el motor aún no ejecuta/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publicar…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar' })).toBeDisabled()
    const summary = screen.getByRole('region', { name: 'Qué define' })
    expect(within(summary).getAllByRole('listitem')[2]).toHaveTextContent(
      'verify verification · Depende de fix (opcional)',
    )

    // Mientras se escribe, el control plane valida el texto como borrador de esta versión.
    fireEvent.change(editor, { target: { value: `${draft.sourceYaml}colr: azul\n` } })
    expect(screen.getByText('Cambios sin guardar')).toBeInTheDocument()
    expect(await screen.findByText('1 error', {}, { timeout: 2000 })).toBeInTheDocument()
    expect(bodies(fetch, 'POST', '/api/workflows/validate').at(-1)).toEqual({
      sourceYaml: `${draft.sourceYaml}colr: azul\n`,
      key: draft.key,
      version: 2,
    })

    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))
    await waitFor(() =>
      expect(bodies(fetch, 'PUT', `/api/workflow-versions/${draft.id}`)).toEqual([
        { sourceYaml: `${draft.sourceYaml}colr: azul\n`, revision: 3 },
      ]),
    )
  })

  it('una versión publicada solo se lee', async () => {
    stubApi({
      [`/api/workflows/${draft.key}`]: workflow,
      [`/api/workflow-versions/${draft.id}`]: draft,
      [`/api/workflow-versions/${v1.id}`]: published,
    })
    renderAt(`/workflows/${draft.key}?version=${v1.id}`, <App />)

    const editor = await screen.findByLabelText(`YAML de ${draft.key} v1`)
    expect(editor).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Guardar' })).toBeNull()
    const versions = screen.getByRole('navigation', { name: 'Versiones' })
    expect(within(versions).getAllByRole('button')).toHaveLength(2)
    expect(within(versions).getByRole('button', { current: 'page' })).toHaveTextContent('v1')
    // Ya hay un borrador (v2): «Editar» no abre otro.
    expect(screen.queryByRole('button', { name: 'Editar' })).toBeNull()
    expect(v2.status).toBe('VALIDATED')
  })

  it('el alta crea el borrador con la plantilla y lleva a su página', async () => {
    const fetch = stubApi({
      'POST /api/workflows': { ...draft, version: 1, key: 'mi-workflow' },
      '/api/workflows/mi-workflow': workflow,
      [`/api/workflow-versions/${draft.id}`]: draft,
      'POST /api/workflows/validate': {
        key: 'mi-workflow',
        validation: draft.validation,
        definition: draft.definition,
      },
    })
    renderAt('/workflows/new', <App />)

    const editor = await screen.findByLabelText('YAML del workflow nuevo')
    expect((editor as HTMLTextAreaElement).value).toMatch(/^id: mi-workflow/)
    fireEvent.click(screen.getByRole('button', { name: 'Crear borrador' }))
    await waitFor(() => expect(bodies(fetch, 'POST', '/api/workflows')).toHaveLength(1))
    expect(await screen.findByRole('navigation', { name: 'Versiones' })).toBeInTheDocument()
  })
})
