import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// Con la máquina cargada (la CI, o los tests de Java a la vez) un render del grafo o del editor
// tarda más que el segundo por defecto de los findBy/waitFor: se esperan hasta 5 s. Un test que
// de verdad falla sigue fallando, solo más tarde.
configure({ asyncUtilTimeout: 5000 })

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
