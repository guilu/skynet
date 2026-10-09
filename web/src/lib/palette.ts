/**
 * Paleta personalizable (UI-G). Se elige un color base por pieza, el del tema claro, y de él se
 * derivan el resto de tokens de esa pieza en los dos temas: la sombra, el fondo suave, la tinta
 * para texto y, en los estados con botón, el botón. La derivación copia la relación que guardan
 * los colores de Skynet con su base (en OKLCH, donde la luminosidad es la que se percibe), así
 * que con los colores por defecto sale exactamente la paleta de `index.css`. Después corrige la
 * tinta y los botones hasta que el texto llegue a AA.
 */

export type Slot = 'primary' | 'ok' | 'warn' | 'bad' | 'live' | 'idle'
export type Mode = 'light' | 'dark'

/** Colores base elegidos, `#rrggbb`; sin color (o `null`), el de Skynet. */
export type Palette = Partial<Record<Slot, string | null>>

/** Tokens derivados de un tema, sin el `--` delante. */
export type Tokens = Record<string, string>

export interface PaletteIssue {
  slot: Slot
  mode: Mode
  message: string
}

export interface DerivedPalette {
  light: Tokens
  dark: Tokens
  issues: PaletteIssue[]
}

export const SLOTS: { slot: Slot; label: string; hint: string }[] = [
  { slot: 'primary', label: 'Acento', hint: 'Botón principal, enlaces, foco y selección.' },
  { slot: 'ok', label: 'Correcto', hint: 'Ejecuciones y tests que terminan bien.' },
  { slot: 'warn', label: 'Aviso', hint: 'Lo que pide atención sin haber fallado.' },
  { slot: 'bad', label: 'Fallo', hint: 'Errores, fallos y acciones que borran.' },
  { slot: 'live', label: 'En curso', hint: 'Lo que está vivo: late en las píldoras.' },
  { slot: 'idle', label: 'En espera', hint: 'Pendiente, cancelado o sin datos.' },
]

/** Los tokens de `index.css` que dependen de cada pieza, en los dos temas. */
const DEFAULTS: Record<Mode, Tokens> = {
  light: {
    primary: '#335fe0',
    'primary-shade': '#2343a8',
    'primary-soft': '#e4ecff',
    'primary-ink': '#2346b0',
    'on-primary': '#ffffff',
    ok: '#22a45a',
    'ok-shade': '#187d44',
    'ok-soft': '#e1f6e9',
    'ok-ink': '#146b3a',
    'ok-btn': '#17804a',
    'ok-btn-shade': '#0e5a33',
    warn: '#f2a52a',
    'warn-shade': '#c27c0a',
    'warn-soft': '#fff2d9',
    'warn-ink': '#7f5200',
    bad: '#ec4646',
    'bad-shade': '#bd2727',
    'bad-soft': '#fde6e6',
    'bad-ink': '#a61f1f',
    'bad-btn': '#d63434',
    'bad-btn-shade': '#a32222',
    live: '#8a5cf5',
    'live-shade': '#6a3ed6',
    'live-soft': '#efe8ff',
    'live-ink': '#5b30c4',
    idle: '#8a97ad',
    'idle-shade': '#66748c',
    'idle-soft': '#eef1f6',
    'idle-ink': '#4c586e',
  },
  dark: {
    primary: '#5b8aff',
    'primary-shade': '#3a63d4',
    'primary-soft': '#1f2d52',
    'primary-ink': '#a9c1ff',
    'on-primary': '#0b1022',
    ok: '#3cd389',
    'ok-shade': '#22a466',
    'ok-soft': '#14352a',
    'ok-ink': '#7fe8b3',
    'ok-btn': '#3cd389',
    'ok-btn-shade': '#22a466',
    warn: '#ffbb4a',
    'warn-shade': '#d08c17',
    'warn-soft': '#3a2c12',
    'warn-ink': '#ffd38a',
    bad: '#ff6b6b',
    'bad-shade': '#d13f3f',
    'bad-soft': '#3d1c22',
    'bad-ink': '#ffa3a3',
    'bad-btn': '#ff6b6b',
    'bad-btn-shade': '#d13f3f',
    live: '#a585ff',
    'live-shade': '#7c58e6',
    'live-soft': '#2a2148',
    'live-ink': '#cbb8ff',
    idle: '#8592ab',
    'idle-shade': '#5f6b83',
    'idle-soft': '#232d42',
    'idle-ink': '#b9c3d6',
  },
}

/** Fondos sobre los que va la tinta (superficie, superficie 2 y página). */
const SURFACES: Record<Mode, string[]> = {
  light: ['#ffffff', '#edf2fb', '#f4f7fc'],
  dark: ['#171f30', '#1f2a40', '#0f1420'],
}

/** Texto sobre los botones de estado (`--on-state-btn`). */
const ON_STATE_BTN: Record<Mode, string> = { light: '#ffffff', dark: '#0b1022' }

/** Contraste mínimo del texto normal (WCAG AA). */
export const AA = 4.5

export const DEFAULT_BASE: Record<Slot, string> = {
  primary: DEFAULTS.light.primary,
  ok: DEFAULTS.light.ok,
  warn: DEFAULTS.light.warn,
  bad: DEFAULTS.light.bad,
  live: DEFAULTS.light.live,
  idle: DEFAULTS.light.idle,
}

export const PRESETS: { id: string; name: string; colors: Palette }[] = [
  { id: 'skynet', name: 'Skynet', colors: {} },
  { id: 'bosque', name: 'Bosque', colors: { primary: '#18794a' } },
  { id: 'laguna', name: 'Laguna', colors: { primary: '#0e7c86' } },
  { id: 'mandarina', name: 'Mandarina', colors: { primary: '#e8590c' } },
  { id: 'frambuesa', name: 'Frambuesa', colors: { primary: '#c2255c' } },
  { id: 'grafito', name: 'Grafito', colors: { primary: '#3d4a63', live: '#2f9bd6' } },
]

/** Tokens de una pieza: el base, la sombra, el fondo suave y la tinta; y los extra de algunas. */
function tokensOf(slot: Slot): string[] {
  const own = [slot, `${slot}-shade`, `${slot}-soft`, `${slot}-ink`]
  if (slot === 'primary') return [...own, 'on-primary']
  if (slot === 'ok' || slot === 'bad') return [...own, `${slot}-btn`, `${slot}-btn-shade`]
  return own
}

/** Normaliza un color a `#rrggbb` en minúsculas, o `null` si no lo es. */
export function normalizeHex(color: string | null | undefined): string | null {
  if (!color) return null
  const value = color.trim().toLowerCase()
  return /^#[0-9a-f]{6}$/.test(value) ? value : null
}

/** Quita los colores vacíos y los que coinciden con los de Skynet. */
export function cleanPalette(palette: Palette): Palette {
  const clean: Palette = {}
  for (const { slot } of SLOTS) {
    const color = normalizeHex(palette[slot])
    if (color && color !== DEFAULT_BASE[slot]) clean[slot] = color
  }
  return clean
}

export function samePalette(a: Palette, b: Palette): boolean {
  const x = cleanPalette(a)
  const y = cleanPalette(b)
  return SLOTS.every(({ slot }) => x[slot] === y[slot])
}

export function derivePalette(palette: Palette): DerivedPalette {
  const light: Tokens = {}
  const dark: Tokens = {}
  const issues: PaletteIssue[] = []
  const clean = cleanPalette(palette)
  for (const { slot } of SLOTS) {
    const base = clean[slot]
    if (!base) {
      for (const token of tokensOf(slot)) {
        light[token] = DEFAULTS.light[token]
        dark[token] = DEFAULTS.dark[token]
      }
      continue
    }
    for (const mode of ['light', 'dark'] as const) {
      const derived = deriveSlot(slot, base, mode)
      Object.assign(mode === 'light' ? light : dark, derived.tokens)
      if (derived.issue) issues.push({ slot, mode, message: derived.issue })
    }
  }
  return { light, dark, issues }
}

/**
 * Tokens de una pieza en un tema a partir de su color base. Exportado para los tests: con el
 * color por defecto debe reproducir los de `index.css`.
 */
export function deriveSlot(
  slot: Slot,
  base: string,
  mode: Mode,
): { tokens: Tokens; issue?: string } {
  const defaults = DEFAULTS[mode]
  const from = toOklch(DEFAULT_BASE[slot])
  const to = toOklch(base)
  const dL = to.l - from.l
  const dH = to.c < 0.02 || from.c < 0.02 ? 0 : to.h - from.h
  // Con una base casi gris (la de «en espera»), la croma se suma en vez de multiplicarse.
  const chroma = (c: number) =>
    from.c < 0.04 ? Math.max(0, c + to.c - from.c) : (c * to.c) / from.c
  const hue = (h: number, c: number) => (from.c < 0.02 || c < 0.02 ? to.h : h + dH)

  const tokens: Tokens = {}
  for (const token of tokensOf(slot)) {
    if (token === 'on-primary') continue
    const original = toOklch(defaults[token])
    // El fondo suave y la tinta conservan su luminosidad: son fondo y texto del tema.
    const keepsLightness = token.endsWith('-soft') || token.endsWith('-ink')
    tokens[token] = fromOklch({
      l: clamp(keepsLightness ? original.l : original.l + dL, 0, 1),
      c: chroma(original.c),
      h: hue(original.h, original.c),
    })
  }
  if (mode === 'light') tokens[slot] = base

  const darker = mode === 'light'
  const ink = `${slot}-ink`
  tokens[ink] = ensureContrast(tokens[ink], [tokens[`${slot}-soft`], ...SURFACES[mode]], darker)
  if (slot === 'ok' || slot === 'bad') {
    const btn = `${slot}-btn`
    const fixed = ensureContrast(tokens[btn], [ON_STATE_BTN[mode]], darker)
    tokens[`${btn}-shade`] = shift(
      tokens[`${btn}-shade`],
      toOklch(fixed).l - toOklch(tokens[btn]).l,
    )
    tokens[btn] = fixed
  }

  let issue: string | undefined
  if (slot === 'primary') {
    // El texto de los botones principales: el de Skynet si llega a AA; si no, el contrario.
    const candidates = [defaults['on-primary'], mode === 'light' ? '#0b1022' : '#ffffff']
    const on = candidates.find((c) => contrast(c, tokens.primary) >= AA)
    tokens['on-primary'] = on ?? candidates[0]
    if (!on) {
      issue =
        'El texto de los botones principales no llega a AA ni en blanco ni en oscuro: elige un color más claro o más oscuro.'
    }
  }
  return { tokens, issue }
}

/** CSS que aplica la paleta encima de `index.css`; vacío con la de Skynet. */
export function paletteCss(palette: Palette): string {
  const clean = cleanPalette(palette)
  if (Object.keys(clean).length === 0) return ''
  const { light, dark } = derivePalette(clean)
  const changed = SLOTS.filter(({ slot }) => clean[slot]).flatMap(({ slot }) => tokensOf(slot))
  const block = (tokens: Tokens) => changed.map((t) => `  --${t}: ${tokens[t]};`).join('\n')
  // `:root:root` gana a los bloques de `index.css` aunque esta hoja se cargue antes.
  return [
    `:root:root {\n${block(light)}\n}`,
    `@media (prefers-color-scheme: dark) {\n:root:root:not([data-theme='light']) {\n${block(dark)}\n}\n}`,
    `:root:root[data-theme='dark'] {\n${block(dark)}\n}`,
  ].join('\n')
}

/** Variables CSS para pintar una vista previa con la paleta en un tema, en `style`. */
export function previewStyle(derived: DerivedPalette, mode: Mode): Record<string, string> {
  const style: Record<string, string> = { ...SURFACE_TOKENS[mode] }
  for (const [token, value] of Object.entries(derived[mode])) style[`--${token}`] = value
  style['--on-state-btn'] = ON_STATE_BTN[mode]
  style['--accent'] = style['--primary']
  style['--focus'] = style['--primary']
  style['--active'] = style['--live']
  return style
}

const SURFACE_TOKENS: Record<Mode, Record<string, string>> = {
  light: {
    '--bg': '#f4f7fc',
    '--surface': '#ffffff',
    '--surface-2': '#edf2fb',
    '--border': '#d9e1ef',
    '--border-strong': '#c3cee2',
    '--fg': '#1a2233',
    '--muted': '#56627b',
    colorScheme: 'light',
  },
  dark: {
    '--bg': '#0f1420',
    '--surface': '#171f30',
    '--surface-2': '#1f2a40',
    '--border': '#2b3854',
    '--border-strong': '#3a4a6c',
    '--fg': '#e9eefa',
    '--muted': '#9eabc6',
    colorScheme: 'dark',
  },
}

/** Contraste WCAG entre dos colores `#rrggbb`. */
export function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)]
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

/** Oscurece (o aclara) el color hasta que contraste AA con todos los fondos. */
function ensureContrast(color: string, backgrounds: string[], darker: boolean): string {
  let current = color
  for (let i = 0; i < 100; i++) {
    if (backgrounds.every((bg) => contrast(current, bg) >= AA)) return current
    current = shift(current, darker ? -0.01 : 0.01)
  }
  return current
}

function shift(color: string, dL: number): string {
  const lch = toOklch(color)
  return fromOklch({ ...lch, l: clamp(lch.l + dL, 0, 1) })
}

// --- Conversión sRGB ↔ OKLCH (https://bottosson.github.io/posts/oklab/) ---

interface Oklch {
  l: number
  c: number
  h: number
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function toLinear(channel: number): number {
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
}

function fromLinear(channel: number): number {
  return channel <= 0.0031308 ? channel * 12.92 : 1.055 * channel ** (1 / 2.4) - 0.055
}

function rgb(hex: string): [number, number, number] {
  const n = Number.parseInt(hex.slice(1), 16)
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255]
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map(toLinear)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

export function toOklch(hex: string): Oklch {
  const [r, g, b] = rgb(hex).map(toLinear)
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
  const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s
  const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
  return { l: L, c: Math.hypot(A, B), h: Math.atan2(B, A) }
}

function oklchToLinear({ l: L, c, h }: Oklch): [number, number, number] {
  const A = c * Math.cos(h)
  const B = c * Math.sin(h)
  const l = (L + 0.3963377774 * A + 0.2158037573 * B) ** 3
  const m = (L - 0.1055613458 * A - 0.0638541728 * B) ** 3
  const s = (L - 0.0894841775 * A - 1.291485548 * B) ** 3
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ]
}

/** OKLCH a `#rrggbb`; si se sale de sRGB, baja la croma hasta que cabe. */
export function fromOklch(color: Oklch): string {
  let { c } = color
  let channels = oklchToLinear(color)
  for (let i = 0; i < 40 && channels.some((v) => v < -0.0005 || v > 1.0005); i++) {
    c *= 0.93
    channels = oklchToLinear({ ...color, c })
  }
  return (
    '#' +
    channels
      .map((v) => Math.round(clamp(fromLinear(clamp(v, 0, 1)), 0, 1) * 255))
      .map((v) => v.toString(16).padStart(2, '0'))
      .join('')
  )
}
