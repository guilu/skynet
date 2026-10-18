import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TabList, TabPanel } from './Tabs'

const TABS = [
  { id: 'a', label: 'Uno' },
  { id: 'b', label: 'Dos' },
  { id: 'c', label: 'Tres' },
  { id: 'd', label: 'Cuatro' },
  { id: 'e', label: 'Cinco' },
] as const
type Id = (typeof TABS)[number]['id']

function Demo({ initial = 'a' as Id }) {
  const [tab, setTab] = useState<Id>(initial)
  return (
    <>
      <TabList
        label="Demo"
        tabs={TABS}
        selected={tab}
        onSelect={setTab}
        idPrefix="t"
        panelId="p"
        overflowMenu
      />
      <TabPanel id="p" labelledBy={`t-${tab}`}>
        Panel {tab}
      </TabPanel>
    </>
  )
}

// jsdom no maqueta: cada pestaña mide 100 px, la fila lo que suman y hay 400 px de sitio.
beforeEach(() => {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    const width = this.getAttribute('role') === 'tab' ? 100 : this.children.length * 100
    return { width } as DOMRect
  })
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('tabs-measure') ? 400 : 0
    },
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  delete (HTMLElement.prototype as { clientWidth?: number }).clientWidth
})

describe('TabList con menú «Más»', () => {
  it('las pestañas que no caben van al menú', async () => {
    render(<Demo />)
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'Uno',
      'Dos',
      'Tres',
      'Más',
    ])
    fireEvent.pointerDown(screen.getByRole('tab', { name: 'Más' }), { button: 0, ctrlKey: false })
    fireEvent.click(await screen.findByRole('menuitemradio', { name: 'Cinco' }))
    // La elegida del menú da nombre al botón, que hace de su pestaña.
    const more = screen.getByRole('tab', { name: 'Cinco' })
    expect(more).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: 'Cinco' })).toHaveTextContent('Panel e')
  })

  it('las flechas también llegan a las pestañas del menú', () => {
    render(<Demo initial="c" />)
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Tres' }), { key: 'ArrowRight' })
    expect(screen.getByRole('tab', { name: 'Cuatro' })).toHaveFocus()
    expect(screen.getByRole('tabpanel', { name: 'Cuatro' })).toBeInTheDocument()
  })
})
