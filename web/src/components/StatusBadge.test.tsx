import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusBadge } from './StatusBadge'
import { statusFace } from './ui/statusIcons'

describe('StatusBadge con Skyny', () => {
  it('pone la expresión de Skyny que toca a cada estado de trabajo', () => {
    expect(statusFace('WAITING_FOR_APPROVAL')).toBe('waiting')
    expect(statusFace('EXECUTING')).toBe('running')
    expect(statusFace('STARTING')).toBe('thinking')
    expect(statusFace('SUCCEEDED')).toBe('success')
    expect(statusFace('FAILED')).toBe('error')
    expect(statusFace('SKIPPED')).toBe('cancelled')
    expect(statusFace('UNRESPONSIVE')).toBe('offline')
    expect(statusFace('ONLINE')).toBe('calm')
    expect(statusFace('OPEN')).toBeUndefined()
  })

  it('dibuja a Skyny en vez del icono y conserva el texto', () => {
    const { container, getByText } = render(<StatusBadge status="EXECUTING" />)
    const pill = container.querySelector('.pill')!
    expect(pill).toHaveClass('pill-skyny', 'pill-active')
    expect(pill).toHaveAttribute('data-face', 'running')
    expect(pill.querySelector('svg.companion')).toHaveAttribute('data-state', 'running')
    expect(getByText('Ejecutando')).toBeInTheDocument()
  })

  it('deja que se fuerce la expresión (Revisando en una verificación)', () => {
    const { container } = render(<StatusBadge status="RUNNING" face="reviewing" />)
    expect(container.querySelector('.pill')).toHaveAttribute('data-face', 'reviewing')
  })

  it('los estados que no son de trabajo siguen con su icono', () => {
    const { container } = render(<StatusBadge status="OPEN" />)
    expect(container.querySelector('.pill-skyny')).toBeNull()
    expect(container.querySelector('.pill-icon')).not.toBeNull()
  })
})
