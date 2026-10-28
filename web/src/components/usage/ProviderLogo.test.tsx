import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ProviderLogo } from './ProviderLogo'

describe('ProviderLogo', () => {
  it.each(['claude-code', 'codex', 'antigravity'])('dibuja el logotipo de %s', (provider) => {
    const { container } = render(<ProviderLogo provider={provider} name="Nombre" />)
    expect(container.querySelector('svg path')).not.toBeNull()
    expect(container.textContent).toBe('')
  })

  it('el degradado de Gemini apunta a su propio id', () => {
    const { container } = render(<ProviderLogo provider="antigravity" name="Antigravity CLI" />)
    const id = container.querySelector('linearGradient')!.id
    expect(container.querySelector('path')!.getAttribute('fill')).toBe(`url(#${id})`)
  })

  it('otro proveedor lleva la inicial de su nombre', () => {
    const { container } = render(<ProviderLogo provider="otro" name="Mistral" />)
    expect(container.querySelector('svg')).toBeNull()
    expect(container.textContent).toBe('M')
  })
})
