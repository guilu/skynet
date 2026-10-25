import map from './navigation-map.json' with { type: 'json' }

/** Inventario de la galería visual: navigation-map.json con sus tipos. */
export type ViewportId = 'desktop' | 'mobile'
export type ThemeId = 'light' | 'dark'
export type LinkType = 'navigation' | 'tab' | 'dialog' | 'menu' | 'action' | 'return'

export interface ScreenLink {
  to: string
  type: LinkType
  label?: string
}

export interface Screen {
  id: string
  order: number
  title: string
  group: string
  route: string
  /** `fullPage` en páginas con scroll; `viewport` donde importa la composición (editor, ejecución). */
  capture: 'fullPage' | 'viewport'
  links: ScreenLink[]
  notes?: string
  /** Punto de entrada: puede no tener enlaces entrantes. */
  entry?: boolean
  /** Estado aislado (error, no encontrado): puede no tener enlaces entrantes. */
  isolated?: boolean
  /** Si la vista no existe o no se puede fijar: se documenta en vez de capturarla. */
  exception?: string
}

export interface Inventory {
  canonical: { viewport: ViewportId; theme: ThemeId }
  viewports: Record<ViewportId, { width: number; height: number }>
  themes: ThemeId[]
  linkTypes: Record<LinkType, string>
  groups: { id: string; title: string }[]
  screens: Screen[]
}

export const inventory = map as Inventory

/** `07-project-work.png`: el orden da el prefijo, así los ficheros se ordenan solos. */
export function fileName(screen: Pick<Screen, 'id' | 'order'>): string {
  return `${String(screen.order).padStart(2, '0')}-${screen.id}.png`
}

/** Pantallas que se capturan (las que no son excepción), en su orden. */
export function capturedScreens(inv: Inventory = inventory): Screen[] {
  return inv.screens.filter((s) => !s.exception).sort((a, b) => a.order - b.order)
}

/** Las combinaciones viewport/tema, en el orden de las carpetas. */
export function variants(inv: Inventory = inventory): { viewport: ViewportId; theme: ThemeId }[] {
  return (Object.keys(inv.viewports) as ViewportId[]).flatMap((viewport) =>
    inv.themes.map((theme) => ({ viewport, theme })),
  )
}

/** Problemas del inventario: ids, órdenes y nombres repetidos, enlaces rotos y huérfanos. */
export function inventoryProblems(inv: Inventory, preparers: Iterable<string>): string[] {
  const problems: string[] = []
  const ids = new Set<string>()
  const orders = new Set<number>()
  const groups = new Set(inv.groups.map((g) => g.id))
  const prepared = new Set(preparers)
  for (const s of inv.screens) {
    if (ids.has(s.id)) problems.push(`id repetido: ${s.id}`)
    ids.add(s.id)
    if (orders.has(s.order)) problems.push(`orden repetido: ${s.order}`)
    orders.add(s.order)
    if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(s.id)) problems.push(`id no válido: ${s.id}`)
    if (!Number.isInteger(s.order) || s.order < 1 || s.order > 99)
      problems.push(`orden fuera de 1-99: ${s.id}`)
    if (!groups.has(s.group)) problems.push(`grupo desconocido en ${s.id}: ${s.group}`)
    if (!s.title.trim()) problems.push(`sin título: ${s.id}`)
    if (!s.exception && !prepared.has(s.id)) problems.push(`sin preparador: ${s.id}`)
    for (const l of s.links) {
      if (!(l.type in inv.linkTypes))
        problems.push(`tipo de enlace desconocido en ${s.id}: ${l.type}`)
    }
  }
  for (const id of prepared) if (!ids.has(id)) problems.push(`preparador sin pantalla: ${id}`)
  const incoming = new Set<string>()
  for (const s of inv.screens) {
    for (const l of s.links) {
      if (!ids.has(l.to)) problems.push(`enlace roto: ${s.id} → ${l.to}`)
      if (l.to !== s.id) incoming.add(l.to)
    }
  }
  for (const s of inv.screens) {
    if (!incoming.has(s.id) && !s.entry && !s.isolated) problems.push(`pantalla huérfana: ${s.id}`)
  }
  if (!(inv.canonical.viewport in inv.viewports) || !inv.themes.includes(inv.canonical.theme))
    problems.push('la combinación canónica no existe')
  const names = inv.screens.map(fileName)
  if (new Set(names).size !== names.length) problems.push('nombres de fichero repetidos')
  return problems
}
