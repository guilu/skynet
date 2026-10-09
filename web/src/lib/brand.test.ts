import { afterEach, describe, expect, it } from 'vitest'
import { applyPalette } from '../appearance'
import { FAVICON_DEFAULT, faviconSvg } from './brand'
import { DEFAULT_BASE } from './palette'
import favicon from '../../public/favicon.svg?raw'

describe('Marca', () => {
  afterEach(() => {
    applyPalette({})
    document.head.querySelector('link[rel="icon"]')?.remove()
  })

  it('el favicon de public/ sale del isotipo con los colores por defecto', () => {
    expect(FAVICON_DEFAULT.primary).toBe(DEFAULT_BASE.primary)
    expect(favicon.trim()).toBe(faviconSvg(FAVICON_DEFAULT.primary, FAVICON_DEFAULT.on))
  })

  it('el favicon sigue al acento y vuelve al estático sin él', () => {
    const icon = document.createElement('link')
    icon.rel = 'icon'
    icon.href = '/favicon.svg'
    document.head.appendChild(icon)

    applyPalette({ primary: '#e8590c' })
    const href = decodeURIComponent(icon.getAttribute('href') ?? '')
    expect(href).toMatch(/^data:image\/svg\+xml,<svg/)
    expect(href).toContain('fill="#e8590c"')

    applyPalette({ ok: '#18794a' })
    expect(icon.getAttribute('href')).toBe('/favicon.svg')
  })
})
