import { afterEach, describe, expect, it } from 'vitest'
import { applyPalette } from '../appearance'
import { FAVICON_DEFAULT, faviconSvg, skynySvg, tint } from './brand'
import { DEFAULT_BASE } from './palette'
import favicon from '../../public/favicon.svg?raw'

describe('Marca', () => {
  afterEach(() => {
    applyPalette({})
    document.head.querySelector('link[rel="icon"]')?.remove()
  })

  it('el favicon de public/ es Skyny con el acento por defecto', () => {
    expect(FAVICON_DEFAULT.primary).toBe(DEFAULT_BASE.primary)
    expect(favicon.trim()).toBe(faviconSvg(FAVICON_DEFAULT.primary))
  })

  it('Skyny lleva cuerpo, tres bandas, visor, tres nodos y ojos', () => {
    const svg = skynySvg('#335fe0')
    expect(svg.match(/<circle [^>]*r="(16|18)"/g)).toHaveLength(3)
    expect(svg.match(/<path d="M60 6[34] L/g)).toHaveLength(3)
    expect(svg).toContain('stop-color="#335fe0"')
    expect(svg.match(/<rect /g)).toHaveLength(2)
  })

  it('aclara colores mezclando con blanco', () => {
    expect(tint('#335fe0', 0)).toBe('#335fe0')
    expect(tint('#335fe0', 1)).toBe('#ffffff')
    expect(tint('#000000', 0.5)).toBe('#808080')
  })

  it('el favicon sigue al acento y vuelve al estático sin él', () => {
    const icon = document.createElement('link')
    icon.rel = 'icon'
    icon.href = '/favicon.svg'
    document.head.appendChild(icon)

    applyPalette({ primary: '#e8590c' })
    const href = decodeURIComponent(icon.getAttribute('href') ?? '')
    expect(href).toMatch(/^data:image\/svg\+xml,<svg/)
    expect(href).toContain('stop-color="#e8590c"')

    applyPalette({ ok: '#18794a' })
    expect(icon.getAttribute('href')).toBe('/favicon.svg')
  })
})
