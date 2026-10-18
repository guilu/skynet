/** Anchos medidos de una lista de pestañas, en píxeles. */
export interface TabWidths {
  /** Cada pestaña tal cual. */
  tabs: number[]
  /** Cada pestaña con la flecha del menú: así se ve en el botón «Más» cuando está elegida. */
  withChevron: number[]
  /** El botón «Más» con su flecha. */
  more: number
  /** Hueco entre pestañas. */
  gap: number
  /** Relleno y borde de la lista. */
  frame: number
}

/**
 * Cuántas pestañas caben en `available` antes del botón «Más», que se lleva el resto. Si la elegida
 * va al menú, el botón muestra su nombre y ocupa más. Siempre queda al menos la primera.
 */
export function fitTabs(widths: TabWidths, available: number, selected: number): number {
  const { tabs, withChevron, more, gap, frame } = widths
  const n = tabs.length
  const sum = (k: number) => tabs.slice(0, k).reduce((a, b) => a + b, 0)
  if (available <= 0 || sum(n) + gap * (n - 1) + frame <= available) return n
  for (let k = n - 1; k > 1; k--) {
    const trigger = selected >= k ? withChevron[selected] : more
    if (sum(k) + gap * k + trigger + frame <= available) return k
  }
  return 1
}
