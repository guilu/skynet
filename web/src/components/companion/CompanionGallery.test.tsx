import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { CompanionGallery } from './CompanionGallery'
import { resetCompanionPrefsCache } from './prefs'

const view = () =>
  render(
    <MemoryRouter>
      <CompanionGallery />
    </MemoryRouter>,
  )

describe('Ajustes › Companion', () => {
  beforeEach(() => {
    localStorage.clear()
    resetCompanionPrefsCache()
  })

  it('enseña los 48 estados en dos grupos y cambia la vista previa al tocar uno', () => {
    view()
    const lists = screen.getAllByRole('list').filter((l) => l.className === 'companion-states')
    expect(lists.map((l) => within(l).getAllByRole('button').length)).toEqual([12, 36])
    fireEvent.click(screen.getByRole('button', { name: 'Delegando' }))
    expect(screen.getByRole('button', { name: 'Delegando' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('heading', { level: 3, name: 'Delegando' })).toBeInTheDocument()
  })

  it('guarda el gorro y los complementos en el navegador', () => {
    view()
    fireEvent.change(screen.getByLabelText('Gorro'), { target: { value: 'crown' } })
    fireEvent.change(screen.getByLabelText('Cara'), { target: { value: 'pipe' } })
    fireEvent.change(screen.getByLabelText('Espalda'), { target: { value: 'jetpack' } })
    expect(JSON.parse(localStorage.getItem('skynet.companion')!)).toMatchObject({
      hat: 'crown',
      face: 'pipe',
      back: 'jetpack',
    })
  })
})
