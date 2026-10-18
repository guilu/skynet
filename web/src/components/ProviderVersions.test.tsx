import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { simulatedProviders } from '../format'
import { ProviderVersions } from './ProviderVersions'

const providers = [
  { id: 'claude-code', version: '2.1.0 (fake-claude)', simulated: true },
  { id: 'codex', version: '0.163.0', simulated: false },
]

describe('ProviderVersions', () => {
  it('marca como «Simulado» solo los CLIs simulados', () => {
    render(<ProviderVersions providers={providers} />)
    expect(screen.getByText(/Claude Code 2\.1\.0 \(fake-claude\)/)).toBeInTheDocument()
    expect(screen.getByText(/Codex 0\.163\.0/)).toBeInTheDocument()
    expect(screen.getAllByText('Simulado')).toHaveLength(1)
  })

  it('lista los nombres de los proveedores simulados', () => {
    expect(simulatedProviders(providers)).toEqual(['Claude Code'])
    expect(simulatedProviders([providers[1]])).toEqual([])
  })
})
