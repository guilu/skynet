import { render } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { CHATGPT } from './chatgpt'
import { CHATGPT_GRADIENTS } from './chatgptColors'
import { Icon } from './Icon'
import css from './ilustrados.css?raw'
import { ILUSTRADOS } from './ilustrados'
import { IconPackContext, setIconPack, type IconPack } from './pack'
import { ICON_NAMES, type IconName } from './registry'
import { variantFor } from './variant'

const draw = (name: IconName, pack: IconPack, props: Partial<Parameters<typeof Icon>[0]> = {}) =>
  render(
    <IconPackContext.Provider value={pack}>
      <Icon name={name} {...props} />
    </IconPackContext.Provider>,
  ).container.querySelector('svg')!

describe('Icon', () => {
  afterEach(() => act(() => setIconPack('lucide')))

  it('pinta todos los nombres en los tres paquetes, siempre decorativos', () => {
    for (const name of ICON_NAMES) {
      for (const pack of ['lucide', 'ilustrados', 'chatgpt'] as const) {
        const svg = draw(name, pack)
        expect(svg, `${name} en ${pack}`).toBeInTheDocument()
        expect(svg).toHaveAttribute('aria-hidden', 'true')
      }
    }
  })

  it('Ilustrados usa su dibujo y, si le falta, el de Lucide', () => {
    expect(draw('dashboard', 'ilustrados')).toHaveAttribute('data-icon', 'dashboard')
    expect(draw('dashboard', 'lucide')).toHaveClass('lucide')
    const missing = ICON_NAMES.find((n) => !ILUSTRADOS[n])!
    expect(draw(missing, 'ilustrados')).toHaveClass('lucide')
  })

  it('la variante del dibujo sale del tamaño; los controles pequeños son siempre de trazo', () => {
    expect(variantFor(48)).toBe('ilustrado')
    expect(variantFor(24)).toBe('compacto')
    expect(variantFor(16)).toBe('trazo')
    expect(draw('runs', 'ilustrados', { size: 48 }).getAttribute('class')).toBe('ico')
    expect(draw('runs', 'ilustrados', { size: 24 })).toHaveClass('compact')
    expect(draw('runs', 'ilustrados', { size: 16 })).toHaveClass('trazo')
    expect(draw('close', 'ilustrados', { size: 40 })).toHaveClass('trazo')
  })

  it('las insignias de estado toman el tono que se les da y repiten el gesto con loop', () => {
    const own = draw('status-error', 'ilustrados')
    expect(own).toHaveClass('badge', 'compact')
    const toned = draw('status-ready', 'ilustrados', { tone: 'live', loop: true })
    expect(toned).toHaveClass('tone-live', 'loop')
  })

  it('ChatGPT tiene dibujo para todos los nombres y pone sus degradados una sola vez', async () => {
    expect(ICON_NAMES.filter((n) => !CHATGPT[n])).toEqual([])
    const svg = draw('dashboard', 'chatgpt', { size: 32 })
    expect(svg).toHaveClass('ico', 'ico-cg')
    expect(svg).not.toHaveClass('small')
    expect(draw('dashboard', 'chatgpt', { size: 16 })).toHaveClass('small')
    expect(document.querySelectorAll('#cg-defs')).toHaveLength(1)
    for (const name of Object.keys(CHATGPT_GRADIENTS))
      expect(document.getElementById(`cg-${name}`)?.tagName, name).toBe('linearGradient')
    // Cada `url(#cg-…)` de los dibujos apunta a un degradado que existe.
    for (const d of Object.values(CHATGPT)) {
      const html = render(<svg>{d!.body}</svg>).container.innerHTML
      for (const m of html.matchAll(/url\(#cg-(\w+)\)/g))
        expect(Object.keys(CHATGPT_GRADIENTS)).toContain(m[1])
    }
  })

  it('las insignias de ChatGPT pintan el disco con el color del tono', () => {
    const toned = draw('status-ready', 'chatgpt', { tone: 'ok', loop: true })
    expect(toned).toHaveClass('tone-ok', 'loop')
    expect(draw('status-error', 'chatgpt')).toHaveClass('tone-bad')
  })

  it('sin paquete forzado, sigue al de la web', () => {
    const { container } = render(<Icon name="settings" size={24} />)
    expect(container.querySelector('svg')).toHaveClass('lucide')
    act(() => setIconPack('ilustrados'))
    expect(container.querySelector('svg')).toHaveAttribute('data-icon', 'settings')
    expect(document.documentElement).toHaveAttribute('data-icons', 'ilustrados')
  })
})

describe('ilustrados.css', () => {
  it('solo anima transform, opacity y el trazo que se dibuja', () => {
    const keyframes = css.match(/@keyframes [\w-]+ \{[\s\S]*?\n\}/g) ?? []
    expect(keyframes.length).toBeGreaterThan(20)
    for (const block of keyframes) {
      const props = [...block.matchAll(/([a-z-]+):/g)].map((m) => m[1])
      for (const p of props) expect(['transform', 'opacity', 'stroke-dashoffset']).toContain(p)
    }
  })

  it('con movimiento reducido no se mueve nada', () => {
    expect(css).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{\s*\.ico \.a \{\s*animation: none !important;/,
    )
  })

  it('cada pieza que se mueve en un dibujo tiene su animación', () => {
    const used = new Set<string>()
    for (const d of [...Object.values(ILUSTRADOS), ...Object.values(CHATGPT)]) {
      const html = render(<svg>{d!.body}</svg>).container.innerHTML
      for (const m of html.matchAll(/\ba-[\w-]+\b/g)) used.add(m[0])
    }
    for (const cls of used) expect(css, cls).toContain(`.ico .${cls} {`)
  })
})
