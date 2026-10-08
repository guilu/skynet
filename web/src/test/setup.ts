import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Sin `globals` de Vitest, Testing Library no desmonta solo entre tests.
afterEach(cleanup)
// Las preferencias guardadas (columnas, barra plegada) no pasan de un test a otro.
afterEach(() => localStorage.clear())

// jsdom no calcula el layout: sin tamaño, las listas virtualizadas no pintarían ninguna fila.
Object.defineProperties(HTMLElement.prototype, {
  offsetHeight: { configurable: true, get: () => 600 },
  offsetWidth: { configurable: true, get: () => 800 },
})

// jsdom no trae ResizeObserver ni scrollIntoView, que usan la paleta ⌘K y los menús.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
Element.prototype.scrollIntoView ??= function () {}
