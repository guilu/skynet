import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, renderAt } from '../../test/render'
import { AssumptionsCard } from './Assumptions'

describe('Los supuestos de la ejecución', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña cada supuesto con su fase', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        mockFetch({
          '/api/workflow-runs/r1/assumptions': [
            { stageKey: 'spec', text: 'El límite es por usuario' },
            { stageKey: 'implementar', text: 'Sin caché' },
          ],
        }),
      ),
    )
    renderAt('/', <AssumptionsCard runId="r1" />)

    const card = await screen.findByRole('region', { name: 'Supuestos para revisar (2)' })
    expect(card).toHaveTextContent('No ha parado nada')
    const items = within(card).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('El límite es por usuario spec')
    expect(items[1]).toHaveTextContent('Sin caché implementar')
  })

  it('sin supuestos no enseña nada', async () => {
    const fetch = vi.fn(mockFetch({ '/api/workflow-runs/r1/assumptions': [] }))
    vi.stubGlobal('fetch', fetch)
    const { container } = renderAt('/', <AssumptionsCard runId="r1" />)
    await vi.waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
