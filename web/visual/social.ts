import {
  capturedScreens,
  fileName,
  type Inventory,
  type SocialFormatId,
  type SocialPick,
  type ThemeId,
} from './inventory.ts'

/**
 * Composiciones para redes (VS-C): la captura de escritorio en una ventana y la de móvil en un
 * teléfono, sobre un fondo con el titular. Las capturas se escalan a lo ancho conservando la
 * proporción (nunca por encima del 100 %) y solo se recortan por abajo y, la ventana, por la
 * derecha cuando sale del lienzo.
 */

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export interface SocialLayout {
  width: number
  height: number
  margin: number
  headline: { y: number; size: number; maxWidth: number }
  /** Ventana de escritorio: barra de `bar` px y debajo la captura a `scale`. */
  window: Rect & { scale: number; bar: number }
  /** Teléfono: marco de `bezel` px y dentro la captura de móvil a `scale`. */
  phone: Rect & { scale: number; bezel: number }
}

/** Escala de cada captura y huecos por formato: lo único que cambia entre los tres. */
const SPEC: Record<
  SocialFormatId,
  { margin: number; header: number; size: number; window: number; phone: number }
> = {
  landscape: { margin: 64, header: 150, size: 46, window: 0.86, phone: 0.62 },
  square: { margin: 56, header: 196, size: 50, window: 0.78, phone: 0.68 },
  portrait: { margin: 56, header: 236, size: 56, window: 0.78, phone: 0.8 },
}

const BAR = 30
const BEZEL = 10
/** Lo que la ventana sobresale del lienzo por abajo cuando la captura da para ello. */
const BLEED = 40

/**
 * Geometría de una composición. `desktopHeight` es el alto de la captura de escritorio: si es
 * corta, la ventana termina donde acaba la imagen en vez de dejar un hueco.
 */
export function socialLayout(
  inv: Inventory,
  format: SocialFormatId,
  desktopHeight: number,
): SocialLayout {
  const { width, height } = inv.social.formats[format]
  const spec = SPEC[format]
  const desktop = inv.viewports.desktop
  const mobile = inv.viewports.mobile
  const top = spec.margin + spec.header
  // +2: el borde de 1 px a cada lado, para que la imagen mida justo ancho × escala.
  const winWidth = Math.round(desktop.width * spec.window) + 2
  const winHeight = Math.min(height - top + BLEED, BAR + Math.round(desktopHeight * spec.window))
  const phoneWidth = Math.round(mobile.width * spec.phone) + 2 * BEZEL
  const phoneHeight = Math.round(mobile.height * spec.phone) + 2 * BEZEL
  // En horizontal caben enteras: ventana y teléfono (que la pisa un 60 %) centrados. En los otros
  // formatos la ventana sale por la derecha y el teléfono se apoya en la esquina inferior.
  const landscape = format === 'landscape'
  const left = landscape ? Math.round((width - winWidth - phoneWidth * 0.4) / 2) : spec.margin
  return {
    width,
    height,
    margin: spec.margin,
    headline: { y: spec.margin, size: spec.size, maxWidth: width - 2 * spec.margin },
    window: { x: left, y: top, width: winWidth, height: winHeight, scale: spec.window, bar: BAR },
    phone: {
      x: landscape
        ? left + winWidth - Math.round(phoneWidth * 0.6)
        : width - spec.margin - phoneWidth,
      y: landscape ? top + 48 : height - spec.margin - phoneHeight,
      width: phoneWidth,
      height: phoneHeight,
      scale: spec.phone,
      bezel: BEZEL,
    },
  }
}

export const SOCIAL_FORMATS: SocialFormatId[] = ['landscape', 'square', 'portrait']

/** `social/square/02-dashboard-light.png`. */
export function socialFile(inv: Inventory, format: SocialFormatId, pick: SocialPick): string {
  const screen = capturedScreens(inv).find((s) => s.id === pick.screen)!
  return `social/${format}/${fileName(screen).replace(/\.png$/, `-${pick.theme}.png`)}`
}

/** Todas las composiciones, en orden estable. */
export function socialFiles(inv: Inventory): string[] {
  return SOCIAL_FORMATS.flatMap((f) => inv.social.picks.map((p) => socialFile(inv, f, p))).sort()
}

const PALETTE: Record<
  ThemeId,
  {
    bg: string
    bg2: string
    fg: string
    muted: string
    frame: string
    bar: string
    dot: string
    phone: string
  }
> = {
  light: {
    bg: '#eef2fb',
    bg2: '#f8f9fc',
    fg: '#2b3445',
    muted: '#5b667c',
    frame: '#d0d8e5',
    bar: '#f1f4f9',
    dot: '#c3ccdb',
    phone: '#2b3445',
  },
  dark: {
    bg: '#0f1522',
    bg2: '#1a2440',
    fg: '#e6eaf2',
    muted: '#9aa4b5',
    frame: '#2a3550',
    bar: '#1d2740',
    dot: '#3a4766',
    phone: '#3a4766',
  },
}

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** La página que se fotografía a tamaño exacto del formato. */
export function renderSocial(options: {
  inv: Inventory
  layout: SocialLayout
  pick: SocialPick
  desktop: string
  mobile: string
  logo: string
  fontCss: string
}): string {
  const { inv, layout: l, pick, desktop, mobile, logo, fontCss } = options
  const c = PALETTE[pick.theme]
  const title = capturedScreens(inv).find((s) => s.id === pick.screen)!.title
  const w = l.window
  const p = l.phone
  const radius = Math.round(34 * (p.scale / 0.7))
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><style>
${fontCss}
* { box-sizing: border-box; }
html, body { margin: 0; width: ${l.width}px; height: ${l.height}px; overflow: hidden; }
body { position: relative; font-family: Barlow, 'Helvetica Neue', Arial, sans-serif; color: ${c.fg};
  background: linear-gradient(160deg, ${c.bg} 0%, ${c.bg2} 100%); }
.brand { position: absolute; left: ${l.margin}px; top: ${l.headline.y}px; display: flex; align-items: center; gap: 12px;
  font-size: 22px; font-weight: 600; color: ${c.muted}; }
.brand svg { width: 38px; height: 38px; }
h1 { position: absolute; left: ${l.margin}px; top: ${l.headline.y + 56}px; margin: 0; max-width: ${l.headline.maxWidth}px;
  font-size: ${l.headline.size}px; line-height: 1.08; font-weight: 600; letter-spacing: -0.01em; }
.caption { font-weight: 400; }
.caption::before { content: '·'; margin-right: 12px; }
.window { position: absolute; left: ${w.x}px; top: ${w.y}px; width: ${w.width}px; height: ${w.height}px;
  border: 1px solid ${c.frame}; border-radius: 14px; overflow: hidden; background: ${c.bar}; }
.window .bar { height: ${w.bar}px; display: flex; align-items: center; gap: 8px; padding: 0 14px; }
.window .bar i { width: 11px; height: 11px; border-radius: 50%; background: ${c.dot}; }
.window img { display: block; width: ${w.width - 2}px; height: auto; }
.phone { position: absolute; left: ${p.x}px; top: ${p.y}px; width: ${p.width}px; height: ${p.height}px;
  padding: ${p.bezel}px; border-radius: ${radius}px; background: ${c.phone}; }
.phone .screen { width: 100%; height: 100%; border-radius: ${radius - p.bezel}px; overflow: hidden; }
.phone img { display: block; width: 100%; height: auto; }
</style></head><body>
<div class="brand">${logo}<span>Skynet</span><span class="caption">${esc(title)}</span></div>
<h1>${esc(pick.headline)}</h1>
<div class="window"><div class="bar"><i></i><i></i><i></i></div><img src="${desktop}" alt=""></div>
<div class="phone"><div class="screen"><img src="${mobile}" alt=""></div></div>
</body></html>
`
}
