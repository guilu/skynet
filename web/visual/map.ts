import {
  capturedScreens,
  fileName,
  variants,
  type Inventory,
  type LinkType,
  type Screen,
} from './inventory.ts'

/**
 * Mapa visual de navegación (VS-B), a partir del mismo inventario que las capturas: disposición
 * determinista por columnas de grupo, conectores con estilo por tipo y leyenda. Aquí solo hay
 * cálculo y texto (SVG, HTML, JSON, README); build-map.ts pone el navegador y los ficheros.
 */

const CARD_W = 300
const THUMB_W = 276
/** Proporción del viewport de escritorio (1440 × 1000). */
const THUMB_H = 192
const CARD_H = 12 + 22 + 18 + 10 + THUMB_H + 12
const COL_GAP = 150
const ROW_GAP = 34
const MARGIN = 48
const HEADER = 170
const GROUP_HEAD = 48
/** Más pantallas que esto en un grupo y se reparte en varias columnas. */
const MAX_ROWS = 7

export const THUMB_SIZE = { width: THUMB_W * 2, height: THUMB_H * 2 }

/** Estilo de cada tipo de conector: color, trazo y grosor. */
export const LINK_STYLES: Record<LinkType, { color: string; dash: string; width: number }> = {
  navigation: { color: '#5b667c', dash: '', width: 1.6 },
  tab: { color: '#335fe0', dash: '8 5', width: 1.6 },
  dialog: { color: '#8b5cf6', dash: '2 4', width: 2 },
  menu: { color: '#d97706', dash: '2 4', width: 2 },
  action: { color: '#16a34a', dash: '', width: 2.6 },
  return: { color: '#9aa4b5', dash: '6 6', width: 1.2 },
}

export interface MapNode {
  screen: Screen
  x: number
  y: number
  /** De otro grupo, en un submapa: se pinta atenuado. */
  muted: boolean
}

export interface MapEdge {
  from: string
  to: string
  type: LinkType
  label?: string
  path: string
}

export interface MapLayout {
  width: number
  height: number
  title: string
  groups: { id: string; title: string; x: number; width: number; count: number }[]
  nodes: MapNode[]
  edges: MapEdge[]
}

/**
 * Coloca las pantallas en columnas por grupo, en el orden del inventario. Con `focus`, el submapa
 * de ese grupo: sus pantallas y, atenuadas, las de otros grupos con las que se enlazan.
 */
export function layoutMap(inv: Inventory, focus?: string): MapLayout {
  const all = capturedScreens(inv)
  const ids = new Set(all.map((s) => s.id))
  let shown = all
  const muted = new Set<string>()
  if (focus) {
    const own = new Set(all.filter((s) => s.group === focus).map((s) => s.id))
    const near = new Set<string>()
    for (const s of all) {
      for (const l of s.links) {
        if (own.has(s.id) && !own.has(l.to)) near.add(l.to)
        if (own.has(l.to) && !own.has(s.id)) near.add(s.id)
      }
    }
    shown = all.filter((s) => own.has(s.id) || near.has(s.id))
    for (const id of near) muted.add(id)
  }

  const groups: MapLayout['groups'] = []
  const nodes: MapNode[] = []
  let x = MARGIN
  let rows = 1
  for (const g of inv.groups) {
    const screens = shown.filter((s) => s.group === g.id)
    if (!screens.length) continue
    const cols = Math.ceil(screens.length / MAX_ROWS)
    const perCol = Math.ceil(screens.length / cols)
    rows = Math.max(rows, perCol)
    const width = cols * CARD_W + (cols - 1) * 40
    groups.push({ id: g.id, title: g.title, x, width, count: screens.length })
    screens.forEach((screen, i) => {
      const col = Math.floor(i / perCol)
      const row = i % perCol
      nodes.push({
        screen,
        x: x + col * (CARD_W + 40),
        y: HEADER + GROUP_HEAD + row * (CARD_H + ROW_GAP),
        muted: muted.has(screen.id),
      })
    })
    x += width + COL_GAP
  }
  const width = x - COL_GAP + MARGIN
  const height = HEADER + GROUP_HEAD + rows * (CARD_H + ROW_GAP) - ROW_GAP + MARGIN

  const byId = new Map(nodes.map((n) => [n.screen.id, n]))
  const raw: Omit<MapEdge, 'path'>[] = []
  for (const n of nodes) {
    for (const l of n.screen.links) {
      if (!ids.has(l.to) || !byId.has(l.to)) continue
      // En un submapa, solo los conectores que tocan el grupo.
      if (focus && n.muted && byId.get(l.to)!.muted) continue
      raw.push({ from: n.screen.id, to: l.to, type: l.type, label: l.label })
    }
  }
  const edges = routeEdges(raw, byId)
  const title = focus
    ? `Mapa de navegación · ${inv.groups.find((g) => g.id === focus)?.title ?? focus}`
    : 'Mapa de navegación de Skynet'
  return { width, height, title, groups, nodes, edges }
}

type Side = 'left' | 'right'

/**
 * Curvas entre tarjetas: hacia la derecha salen por el lado derecho y entran por el izquierdo, y
 * al revés; dentro de la misma columna, de lado derecho a lado derecho con una curva hacia fuera.
 * Los conectores que comparten lado se reparten a lo alto de la tarjeta para no pisarse.
 */
function routeEdges(raw: Omit<MapEdge, 'path'>[], byId: Map<string, MapNode>): MapEdge[] {
  const plan = raw.map((e) => {
    const a = byId.get(e.from)!
    const b = byId.get(e.to)!
    const [out, into]: [Side, Side] =
      b.x > a.x ? ['right', 'left'] : b.x < a.x ? ['left', 'right'] : ['right', 'right']
    return { e, a, b, out, into }
  })
  const slots = new Map<string, number[]>()
  const slot = (id: string, side: Side, index: number) => {
    const key = `${id}:${side}`
    const list = slots.get(key) ?? []
    list.push(index)
    slots.set(key, list)
  }
  plan.forEach((p, i) => {
    slot(p.a.screen.id, p.out, i)
    slot(p.b.screen.id, p.into, i)
  })
  const anchor = (n: MapNode, side: Side, index: number) => {
    const list = slots.get(`${n.screen.id}:${side}`)!
    const k = list.indexOf(index)
    const top = n.y + 28
    const span = CARD_H - 44
    return {
      x: side === 'right' ? n.x + CARD_W : n.x,
      y: Math.round(top + ((k + 1) * span) / (list.length + 1)),
    }
  }
  return plan.map((p, i) => {
    const s = anchor(p.a, p.out, i)
    const t = anchor(p.b, p.into, i)
    const pull = p.out === p.into ? 70 : Math.max(60, Math.abs(t.x - s.x) / 3)
    const c1 = s.x + (p.out === 'right' ? pull : -pull)
    const c2 = t.x + (p.into === 'right' ? pull : -pull)
    return { ...p.e, path: `M${s.x},${s.y} C${c1},${s.y} ${c2},${t.y} ${t.x},${t.y}` }
  })
}

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** Corta un texto para que quepa en la tarjeta (las tarjetas no se redimensionan). */
const clip = (s: string, max: number) => (s.length > max ? `${s.slice(0, max - 1)}…` : s)

export interface SvgOptions {
  /** URL de la miniatura de cada pantalla: data: (portable) o una ruta relativa. */
  image: (screen: Screen) => string
  /** Enlace de cada tarjeta (la captura grande); sin él, las tarjetas no enlazan. */
  href?: (screen: Screen) => string
  /** Pie bajo el título. */
  subtitle?: string
}

/** El mapa como SVG autónomo: importable en Figma y Miro si las miniaturas van como data:. */
export function renderSvg(layout: MapLayout, inv: Inventory, options: SvgOptions): string {
  const font = `font-family="Barlow, 'Helvetica Neue', Arial, sans-serif"`
  const markers = (Object.keys(LINK_STYLES) as LinkType[])
    .map(
      (t) =>
        `<marker id="arrow-${t}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${LINK_STYLES[t].color}"/></marker>`,
    )
    .join('')
  const legend = (Object.keys(LINK_STYLES) as LinkType[])
    .map((t, i) => {
      const s = LINK_STYLES[t]
      const lx = MARGIN + i * 230
      return `<g transform="translate(${lx},120)"><line x1="0" y1="0" x2="56" y2="0" stroke="${s.color}" stroke-width="${s.width}" stroke-dasharray="${s.dash}" marker-end="url(#arrow-${t})"/><text x="68" y="5" font-size="15" fill="#3d4759" ${font}>${esc(inv.linkTypes[t])}</text></g>`
    })
    .join('')
  const groups = layout.groups
    .map(
      (g) =>
        `<g><rect x="${g.x - 16}" y="${HEADER - 4}" width="${g.width + 32}" height="${layout.height - HEADER - MARGIN + 20}" rx="18" fill="#f1f4f9"/><text x="${g.x}" y="${HEADER + 28}" font-size="20" font-weight="600" fill="#2b3445" ${font}>${esc(g.title)}</text><text x="${g.x + g.width}" y="${HEADER + 28}" font-size="15" text-anchor="end" fill="#5b667c" ${font}>${g.count}</text></g>`,
    )
    .join('')
  const edges = layout.edges
    .map((e) => {
      const s = LINK_STYLES[e.type]
      const title = `${e.from} → ${e.to} (${inv.linkTypes[e.type]}${e.label ? `: ${e.label}` : ''})`
      return `<path class="edge edge-${e.type}" data-from="${e.from}" data-to="${e.to}" d="${e.path}" fill="none" stroke="${s.color}" stroke-width="${s.width}" stroke-dasharray="${s.dash}" marker-end="url(#arrow-${e.type})" opacity="0.85"><title>${esc(title)}</title></path>`
    })
    .join('')
  const cards = layout.nodes
    .map((n) => {
      const s = n.screen
      const img = options.image(s)
      const card = [
        `<rect x="0" y="0" width="${CARD_W}" height="${CARD_H}" rx="12" fill="#ffffff" stroke="#d0d8e5"/>`,
        `<rect x="12" y="12" width="30" height="22" rx="6" fill="#e4ecff"/>`,
        `<text x="27" y="28" font-size="13" font-weight="600" text-anchor="middle" fill="#2346b0" ${font}>${String(s.order).padStart(2, '0')}</text>`,
        `<text x="50" y="29" font-size="16" font-weight="600" fill="#2b3445" ${font}>${esc(clip(s.title, 30))}</text>`,
        `<text x="12" y="52" font-size="12" fill="#5b667c" font-family="'JetBrains Mono', Menlo, monospace">${esc(clip(s.route, 40))}</text>`,
        `<image x="12" y="62" width="${THUMB_W}" height="${THUMB_H}" preserveAspectRatio="xMidYMin slice" href="${img}" xlink:href="${img}"/>`,
        `<rect x="12" y="62" width="${THUMB_W}" height="${THUMB_H}" fill="none" stroke="#e3e8f0"/>`,
        `<title>${esc(`${s.title} · ${s.route}`)}</title>`,
      ].join('')
      const body = `<g class="node" data-id="${s.id}" transform="translate(${n.x},${n.y})"${n.muted ? ' opacity="0.45"' : ''}>${card}</g>`
      return options.href ? `<a href="${esc(options.href(s))}" target="_blank">${body}</a>` : body
    })
    .join('')
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}">`,
    `<defs>${markers}</defs>`,
    `<rect width="100%" height="100%" fill="#ffffff"/>`,
    `<text x="${MARGIN}" y="${MARGIN + 22}" font-size="30" font-weight="600" fill="#2b3445" ${font}>${esc(layout.title)}</text>`,
    `<text x="${MARGIN}" y="${MARGIN + 52}" font-size="16" fill="#5b667c" ${font}>${esc(options.subtitle ?? `${layout.nodes.length} pantallas · capturas de escritorio, tema claro · generado de web/visual/navigation-map.json`)}</text>`,
    legend,
    groups,
    `<g class="edges">${edges}</g>`,
    `<g class="nodes">${cards}</g>`,
    `</svg>`,
  ].join('\n')
}

/** Captura de cada combinación, relativa a la carpeta map/. */
function variantLinks(inv: Inventory, s: Screen) {
  return variants(inv).map((v) => ({
    label: `${v.viewport === 'desktop' ? 'Escritorio' : 'Móvil'} ${v.theme === 'light' ? 'claro' : 'oscuro'}`,
    href: `../${v.viewport}/${v.theme}/${fileName(s)}`,
  }))
}

/** El manifiesto resuelto: pantallas con sus ficheros y posición, y conectores con su tipo. */
export function resolvedMap(inv: Inventory, layout: MapLayout) {
  return {
    canonical: inv.canonical,
    viewports: inv.viewports,
    themes: inv.themes,
    linkTypes: inv.linkTypes,
    groups: inv.groups,
    screens: layout.nodes.map((n) => ({
      id: n.screen.id,
      order: n.screen.order,
      title: n.screen.title,
      group: n.screen.group,
      route: n.screen.route,
      capture: n.screen.capture,
      notes: n.screen.notes ?? null,
      entry: !!n.screen.entry,
      isolated: !!n.screen.isolated,
      thumbnail: `thumbs/${fileName(n.screen).replace(/\.png$/, '.jpg')}`,
      files: Object.fromEntries(
        variants(inv).map((v) => [
          `${v.viewport}/${v.theme}`,
          `${v.viewport}/${v.theme}/${fileName(n.screen)}`,
        ]),
      ),
      position: { x: n.x, y: n.y, width: CARD_W, height: CARD_H },
      links: n.screen.links,
    })),
    exceptions: inv.screens
      .filter((s) => s.exception)
      .map((s) => ({ id: s.id, reason: s.exception })),
  }
}

/**
 * navigation-map.html: el SVG con miniaturas relativas, zoom con la rueda o los botones, arrastrar
 * para moverse y, al pulsar una pantalla, su ficha con la ruta y las capturas de cada combinación.
 * Funciona como fichero local, sin servidor ni credenciales.
 */
export function renderHtml(layout: MapLayout, inv: Inventory, fontCss: string): string {
  const svg = renderSvg(layout, inv, {
    image: (s) => `thumbs/${fileName(s).replace(/\.png$/, '.jpg')}`,
  })
  const info = Object.fromEntries(
    layout.nodes.map((n) => [
      n.screen.id,
      {
        title: n.screen.title,
        route: n.screen.route,
        notes: n.screen.notes ?? '',
        variants: variantLinks(inv, n.screen),
        links: n.screen.links.map((l) => ({
          to: l.to,
          title: inv.screens.find((s) => s.id === l.to)?.title ?? l.to,
          type: inv.linkTypes[l.type],
          label: l.label ?? '',
        })),
      },
    ]),
  )
  return `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mapa de navegación de Skynet</title>
<style>
${fontCss}
:root { color-scheme: light; }
* { box-sizing: border-box; }
html, body { margin: 0; height: 100%; background: #ffffff; color: #2b3445; font-family: Barlow, 'Helvetica Neue', Arial, sans-serif; }
#stage { position: fixed; inset: 0; overflow: hidden; cursor: grab; touch-action: none; }
#stage.dragging { cursor: grabbing; }
#stage svg { width: 100%; height: 100%; display: block; }
.node { cursor: pointer; }
.node:hover > rect:first-child, .node.on > rect:first-child { stroke: #335fe0; stroke-width: 2.5; }
.edge.dim { opacity: 0.12; }
.edge.hot { opacity: 1; stroke-width: 3; }
.tools { position: fixed; left: 16px; bottom: 16px; display: flex; gap: 8px; }
.tools button { font: inherit; font-size: 18px; width: 40px; height: 40px; border-radius: 10px; border: 1px solid #d0d8e5; background: #fff; color: #2b3445; cursor: pointer; }
.tools button.wide { width: auto; padding: 0 14px; font-size: 15px; }
#panel { position: fixed; top: 16px; right: 16px; width: 340px; max-width: calc(100% - 32px); max-height: calc(100% - 32px); overflow: auto; background: #fff; border: 1px solid #d0d8e5; border-radius: 14px; padding: 18px 20px; display: none; }
#panel.open { display: block; }
#panel h2 { margin: 0 32px 4px 0; font-size: 20px; font-weight: 600; }
#panel code { font-family: 'JetBrains Mono', Menlo, monospace; font-size: 12px; color: #5b667c; }
#panel p { color: #5b667c; font-size: 14px; }
#panel h3 { font-size: 14px; font-weight: 600; margin: 14px 0 6px; }
#panel ul { margin: 0; padding-left: 18px; font-size: 14px; }
#panel a { color: #2346b0; }
#panel .close { position: absolute; top: 12px; right: 12px; border: 0; background: none; font-size: 22px; cursor: pointer; color: #5b667c; }
.hint { position: fixed; left: 50%; bottom: 16px; transform: translateX(-50%); font-size: 13px; color: #5b667c; background: #fff; border: 1px solid #e3e8f0; border-radius: 999px; padding: 6px 14px; }
@media (max-width: 640px) { .hint { display: none; } }
</style>
</head>
<body>
<div id="stage">${svg}</div>
<aside id="panel" aria-live="polite"><button class="close" aria-label="Cerrar">×</button><div id="panel-body"></div></aside>
<div class="tools"><button id="zoom-in" aria-label="Acercar">+</button><button id="zoom-out" aria-label="Alejar">−</button><button id="zoom-fit" class="wide">Ver todo</button></div>
<div class="hint">Rueda para acercar · arrastra para moverte · pulsa una pantalla para ver sus capturas</div>
<script>
const INFO = ${JSON.stringify(info)};
const W = ${layout.width}, H = ${layout.height};
const stage = document.getElementById('stage');
const svg = stage.querySelector('svg');
svg.removeAttribute('width'); svg.removeAttribute('height');
let view = { x: 0, y: 0, w: W, h: H };
const apply = () => svg.setAttribute('viewBox', view.x + ' ' + view.y + ' ' + view.w + ' ' + view.h);
const fit = () => { view = { x: 0, y: 0, w: W, h: H }; apply(); };
const zoom = (factor, cx, cy) => {
  const r = svg.getBoundingClientRect();
  const px = view.x + ((cx ?? r.width / 2) / r.width) * view.w;
  const py = view.y + ((cy ?? r.height / 2) / r.height) * view.h;
  const w = Math.min(W * 3, Math.max(300, view.w * factor));
  const h = w * (view.h / view.w);
  view = { x: px - ((px - view.x) * w) / view.w, y: py - ((py - view.y) * h) / view.h, w, h };
  apply();
};
stage.addEventListener('wheel', (e) => { e.preventDefault(); const r = svg.getBoundingClientRect(); zoom(e.deltaY > 0 ? 1.15 : 1 / 1.15, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
let drag = null;
stage.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, y: e.clientY, moved: false }; stage.setPointerCapture(e.pointerId); });
stage.addEventListener('pointermove', (e) => {
  if (!drag) return;
  const r = svg.getBoundingClientRect();
  const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
  if (Math.abs(dx) + Math.abs(dy) > 3) { drag.moved = true; stage.classList.add('dragging'); }
  view.x -= (dx / r.width) * view.w; view.y -= (dy / r.height) * view.h;
  drag.x = e.clientX; drag.y = e.clientY; apply();
});
stage.addEventListener('pointerup', (e) => {
  const moved = drag && drag.moved; drag = null; stage.classList.remove('dragging');
  if (moved) return;
  const node = document.elementFromPoint(e.clientX, e.clientY)?.closest('.node');
  if (node) select(node.dataset.id);
});
document.getElementById('zoom-in').onclick = () => zoom(1 / 1.3);
document.getElementById('zoom-out').onclick = () => zoom(1.3);
document.getElementById('zoom-fit').onclick = fit;
const panel = document.getElementById('panel');
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
function select(id) {
  const i = INFO[id];
  document.querySelectorAll('.node').forEach((n) => n.classList.toggle('on', n.dataset.id === id));
  document.querySelectorAll('.edge').forEach((p) => {
    const hot = p.dataset.from === id || p.dataset.to === id;
    p.classList.toggle('hot', hot); p.classList.toggle('dim', !hot);
  });
  document.getElementById('panel-body').innerHTML =
    '<h2>' + esc(i.title) + '</h2><code>' + esc(i.route) + '</code>' +
    (i.notes ? '<p>' + esc(i.notes) + '</p>' : '') +
    '<h3>Capturas</h3><ul>' + i.variants.map((v) => '<li><a href="' + esc(v.href) + '" target="_blank">' + esc(v.label) + '</a></li>').join('') + '</ul>' +
    (i.links.length ? '<h3>Lleva a</h3><ul>' + i.links.map((l) => '<li><a href="#" data-go="' + esc(l.to) + '">' + esc(l.title) + '</a> · ' + esc(l.type) + (l.label ? ' (' + esc(l.label) + ')' : '') + '</li>').join('') + '</ul>' : '') +
    '<p>En Git solo están las capturas de escritorio en claro; las demás, en el ZIP o el artifact de la Action.</p>';
  panel.classList.add('open');
}
panel.addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go) { e.preventDefault(); select(go.dataset.go); }
});
panel.querySelector('.close').onclick = () => {
  panel.classList.remove('open');
  document.querySelectorAll('.node.on').forEach((n) => n.classList.remove('on'));
  document.querySelectorAll('.edge').forEach((p) => p.classList.remove('hot', 'dim'));
};
fit();
</script>
</body>
</html>
`
}

/** map/README.md: qué hay, cómo abrirlo, regenerarlo e importarlo en Figma y Miro. */
export function mapReadme(inv: Inventory, groupFiles: { title: string; file: string }[]): string {
  const screens = capturedScreens(inv)
  return [
    '# Mapa de navegación de Skynet',
    '',
    '<!-- Generado por web/visual/build-map.ts a partir de web/visual/navigation-map.json: no editar a mano. -->',
    '',
    `Las ${screens.length} pantallas de la galería, con sus relaciones de navegación, agrupadas por zona. Cada`,
    'tarjeta lleva la miniatura de escritorio en claro, el título, la ruta y su número de captura.',
    '',
    '| Fichero | Para qué |',
    '| --- | --- |',
    '| `navigation-map.html` | Mapa navegable: rueda para acercar, arrastrar para moverse; al pulsar una pantalla, su ruta, notas, capturas de cada combinación y a dónde lleva. |',
    '| `navigation-map.svg` | Vectorial con las miniaturas incrustadas: para Figma y Miro. |',
    '| `navigation-map.png` | Vista de conjunto para compartir. |',
    '| `navigation-map.pdf` | Una página con el mapa entero, para revisar y anotar. |',
    '| `navigation-map.json` | El manifiesto resuelto: pantallas, ficheros, posición y conectores. |',
    '| `thumbs/` | Las miniaturas que usa el HTML. |',
    '| `groups/` | Un submapa SVG por zona, con sus vecinas de otras zonas atenuadas. No se versiona (repite las miniaturas): está en el ZIP y en el artifact de la Action. |',
    '',
    '## Leyenda',
    '',
    '| Conector | Significa |',
    '| --- | --- |',
    ...(Object.keys(LINK_STYLES) as LinkType[]).map((t) => {
      const s = LINK_STYLES[t]
      const line = s.dash ? (s.dash.startsWith('2') ? 'punteado' : 'discontinuo') : 'continuo'
      return `| ${line}, ${s.color}${t === 'action' ? ', grueso' : ''} | ${inv.linkTypes[t]} |`
    }),
    '',
    '## Submapas',
    '',
    'En el ZIP, en el artifact de la Action o tras generar la galería en local:',
    '',
    ...groupFiles.map((g) => `- [${g.title}](groups/${g.file})`),
    '',
    '## Abrir',
    '',
    'Abre `navigation-map.html` con doble clic: funciona como fichero local, sin servidor. Si tu',
    'navegador no carga las miniaturas desde `file://`, sirve la carpeta de la galería:',
    '',
    '```bash',
    'cd docs/screenshots && python3 -m http.server 8000',
    '# http://localhost:8000/map/navigation-map.html',
    '```',
    '',
    '## Importar en Figma y Miro',
    '',
    '- **Figma**: arrastra `navigation-map.svg` al lienzo (o *Archivo → Importar*). Llega como un',
    '  grupo editable: tarjetas, textos y conectores sueltos, con las miniaturas incrustadas. Para',
    '  trabajar una pantalla a tamaño real, arrastra también su PNG de `../desktop/light/` (o de las',
    '  otras carpetas del ZIP) y úsalo como frame.',
    '- **Miro**: sube `navigation-map.svg` (o el PDF / PNG para anotar encima). Para montar variantes',
    '  de un flujo, sube las capturas sueltas que necesites.',
    '',
    'Los nombres (`NN-id.png`) y el orden no cambian entre generaciones: al regenerar, cada imagen',
    'sustituye a la anterior sin ambigüedad.',
    '',
    '## Regenerar',
    '',
    'Sale con la galería (`cd web && npm run screenshots`). Para rehacer solo el mapa a partir de las',
    'capturas que ya hay, sin volver a capturar: `npm run screenshots:map`.',
    '',
  ].join('\n')
}
