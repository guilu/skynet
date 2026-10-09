import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { JsonView } from './JsonView'

describe('JsonView', () => {
  it('pliega por niveles y pinta todo como texto', () => {
    const { container } = render(
      <JsonView
        label="Evento"
        value={{ type: 'agent.tool.started', payload: { input: { html: '<img src=x>' } }, n: 3 }}
      />,
    )
    expect(screen.getByText('“agent.tool.started”')).toBeInTheDocument()
    expect(screen.getByText('3')).toHaveClass('json-number')
    // Los dos primeros niveles salen abiertos; el tercero, plegado.
    const branches = container.querySelectorAll('details')
    expect([...branches].map((d) => d.open)).toEqual([true, true, false])
    expect(container.querySelector('img')).toBeNull()
  })

  it('recorta las cadenas largas hasta pedirlas enteras', () => {
    render(<JsonView label="Salida" value={{ output: 'y'.repeat(500) }} />)
    expect(screen.getByText(/y{400}…/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Ver completo/ }))
    expect(screen.getByText(`“${'y'.repeat(500)}”`)).toBeInTheDocument()
  })
})
