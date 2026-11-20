import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TimelineFiltersBar } from './TimelineFiltersBar'

const narrow = (matches: boolean) =>
  vi.stubGlobal(
    'matchMedia',
    () =>
      ({
        matches,
        addEventListener: () => {},
        removeEventListener: () => {},
      }) as unknown as MediaQueryList,
  )

describe('Filtros del timeline', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('en una fila, con los selectores a la vista', () => {
    narrow(false)
    render(<TimelineFiltersBar filters={{}} stages={[]} agents={[]} onChange={() => {}} />)
    expect(screen.getByRole('combobox', { name: 'Tipo' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Filtrar' })).not.toBeInTheDocument()
  })

  it('en el móvil, tras un botón «Filtrar» que los abre en un panel', () => {
    narrow(true)
    const onChange = vi.fn()
    render(
      <TimelineFiltersBar
        filters={{ origin: 'github' }}
        stages={[]}
        agents={[]}
        onChange={onChange}
      />,
    )
    expect(screen.queryByRole('combobox', { name: 'Tipo' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Filtrar: 1 activo' }))
    const sheet = screen.getByRole('dialog', { name: 'Filtrar' })
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Severidad' }), {
      target: { value: 'error' },
    })
    expect(onChange).toHaveBeenCalledWith({ origin: 'github', severity: 'error' })
    fireEvent.click(within(sheet).getByRole('button', { name: 'Quitar filtros' }))
    expect(onChange).toHaveBeenLastCalledWith({})
  })
})
