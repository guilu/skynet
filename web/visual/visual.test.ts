import { describe, expect, it } from 'vitest'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { checkZip, expectedFiles, finishGallery, ZIP_NAME, type Rendered } from './gallery.ts'
import {
  capturedScreens,
  fileName,
  inventory,
  inventoryProblems,
  type Inventory,
} from './inventory.ts'
import { pngSize } from './png.ts'
import { scanText } from './privacy.ts'
import { galleryReadme } from './readme.ts'
import { PREPARERS } from './screens.ts'
import { buildZip, zipEntries } from './zip.ts'

describe('inventario de la galería', () => {
  it('no tiene ids repetidos, enlaces rotos, huérfanos ni pantallas sin preparador', () => {
    expect(inventoryProblems(inventory, Object.keys(PREPARERS))).toEqual([])
  })

  it('da nombres estables y ordenables', () => {
    const names = capturedScreens().map(fileName)
    expect(names[0]).toBe('01-login.png')
    expect([...names].sort()).toEqual(names)
  })

  it('detecta enlaces rotos, huérfanos y repetidos', () => {
    const broken: Inventory = {
      ...inventory,
      screens: [
        {
          id: 'a',
          order: 1,
          title: 'A',
          group: 'access',
          route: '/',
          capture: 'viewport',
          entry: true,
          links: [{ to: 'x', type: 'navigation' }],
        },
        {
          id: 'b',
          order: 1,
          title: 'B',
          group: 'access',
          route: '/b',
          capture: 'viewport',
          links: [],
        },
      ],
    }
    expect(inventoryProblems(broken, ['a', 'b'])).toEqual(
      expect.arrayContaining(['orden repetido: 1', 'enlace roto: a → x', 'pantalla huérfana: b']),
    )
  })

  it('el índice enlaza cada captura canónica', () => {
    const readme = galleryReadme(inventory)
    for (const s of capturedScreens()) expect(readme).toContain(`desktop/light/${fileName(s)}`)
  })
})

describe('escaneo de privacidad', () => {
  it('encuentra secretos, correos y rutas personales', () => {
    const text =
      'ghp_abcdefghijklmnopqrstuvwx0123 AKIAABCDEFGHIJKLMNOP ana@gmail.com /home/ana/x /Users/ana'
    expect(scanText('t', text).map((f) => f.rule)).toEqual([
      'correo',
      'token de GitHub',
      'clave de AWS',
      'ruta personal',
      'ruta personal',
    ])
  })

  it('deja pasar lo sintético del entorno aislado', () => {
    const text = 'demo@example.invalid /tmp/skynet-visual/analizador-texto /home/dev/.claude'
    expect(scanText('t', text)).toEqual([])
  })

  it('encuentra los datos de la máquina como palabra', () => {
    expect(scanText('t', 'runner en portatil-diego', ['portatil-diego'])).toHaveLength(1)
    expect(scanText('t', 'nada que ver', ['portatil-diego'])).toEqual([])
  })
})

describe('ZIP determinista', () => {
  const files = [
    { name: 'b.txt', data: Buffer.from('dos') },
    { name: 'a/uno.txt', data: Buffer.from('uno') },
  ]

  it('da los mismos bytes con las entradas en otro orden', () => {
    expect(buildZip(files).equals(buildZip([...files].reverse()))).toBe(true)
  })

  it('guarda cada fichero tal cual, ordenado por nombre', () => {
    const zip = buildZip(files)
    const nameLength = zip.readUInt16LE(26)
    expect(zip.subarray(30, 30 + nameLength).toString()).toBe('a/uno.txt')
    expect(zip.readUInt16LE(8)).toBe(0) // sin comprimir
    expect(zip.subarray(30 + nameLength, 30 + nameLength + 3).toString()).toBe('uno')
  })
})

describe('tamaño de un PNG', () => {
  it('lee ancho y alto de la cabecera', () => {
    const png = Buffer.alloc(24)
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(png)
    png.writeUInt32BE(1440, 16)
    png.writeUInt32BE(1000, 20)
    expect(pngSize(png)).toEqual({ width: 1440, height: 1000 })
  })
})

/** Cabecera de un PNG con ese tamaño: es lo único que leen las comprobaciones. */
function fakePng(width: number, height: number) {
  const png = Buffer.alloc(24)
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(png)
  png.writeUInt32BE(width, 16)
  png.writeUInt32BE(height, 20)
  return png
}

/** Una galería completa de mentira (PNG y texto capturado) en un directorio temporal. */
async function fakeGallery(change: (r: Rendered) => Rendered = (r) => r) {
  const root = await mkdtemp(join(tmpdir(), 'galeria-'))
  const galleryDir = join(root, 'galeria')
  const renderedDir = join(root, 'rendered')
  for (const rel of expectedFiles(inventory)) {
    const [viewport, theme, name] = rel.split('/') as [
      'desktop' | 'mobile',
      'light' | 'dark',
      string,
    ]
    const { width, height } = inventory.viewports[viewport]
    await mkdir(join(galleryDir, viewport, theme), { recursive: true })
    await writeFile(join(galleryDir, rel), fakePng(width, height))
    const screen = capturedScreens().find((s) => fileName(s) === name)!
    const rendered: Rendered = {
      screen: screen.id,
      viewport,
      theme,
      dataTheme: theme,
      innerWidth: width,
      text: `${screen.title} · DEMO · /tmp/skynet-visual/analizador-texto`,
      api: [{ author: 'demo@example.invalid' }],
    }
    const file = join(renderedDir, `${viewport}-${theme}`, `${screen.id}.json`)
    await mkdir(dirname(file), { recursive: true })
    await writeFile(file, JSON.stringify(change(rendered)))
  }
  return { root, galleryDir, renderedDir }
}

const finish = (g: { galleryDir: string; renderedDir: string }) =>
  finishGallery({ ...g, inv: inventory, secrets: [] })

const zipExists = (dir: string) =>
  stat(join(dir, ZIP_NAME)).then(
    () => true,
    () => false,
  )

describe('cierre de la galería', () => {
  it('el mismo manifiesto da siempre las mismas rutas, en el mismo orden', () => {
    const files = expectedFiles(inventory)
    expect(expectedFiles(inventory)).toEqual(files)
    expect(files).toHaveLength(capturedScreens().length * 4)
    expect(new Set(files).size).toBe(files.length)
    expect(files[0]).toBe('desktop/light/01-login.png')
  })

  it('con todo limpio escribe el README y un ZIP con las capturas y el README, ordenado', async () => {
    const g = await fakeGallery()
    try {
      expect((await finish(g)).problems).toEqual([])
      const zip = await readFile(join(g.galleryDir, ZIP_NAME))
      expect(zipEntries(zip)).toEqual([...expectedFiles(inventory), 'README.md'].sort())
      expect(checkZip(zip, inventory)).toEqual([])
      expect(await readFile(join(g.galleryDir, 'README.md'), 'utf8')).toContain('01-login.png')
    } finally {
      await rm(g.root, { recursive: true })
    }
  })

  it.each([
    ['correo', 'contacto: ana@gmail.com', 'correo «ana@gmail.com»'],
    [
      'token',
      'clave ghp_abcdefghijklmnopqrstuvwx0123',
      'token de GitHub «ghp_abcdefghijklmnopqrstuvwx0123»',
    ],
    ['clave de AWS', 'AKIAABCDEFGHIJKLMNOP', 'clave de AWS «AKIAABCDEFGHIJKLMNOP»'],
    ['ruta de home', 'worktree en /home/ana/proyectos', 'ruta personal «/home/ana/»'],
  ])(
    'falla con un %s en lo capturado, dice qué y dónde, y no crea el ZIP',
    async (_, leak, rule) => {
      const g = await fakeGallery((r) =>
        r.screen === 'dashboard' && r.viewport === 'desktop' && r.theme === 'dark'
          ? { ...r, text: `${r.text} ${leak}` }
          : r,
      )
      try {
        const { problems } = await finish(g)
        expect(problems).toEqual([`privacidad: ${rule} en desktop-dark/dashboard (texto)`])
        expect(await zipExists(g.galleryDir)).toBe(false)
      } finally {
        await rm(g.root, { recursive: true })
      }
    },
  )

  it('también mira las respuestas de la API', async () => {
    const g = await fakeGallery((r) =>
      r.screen === 'runs' && r.viewport === 'mobile' && r.theme === 'light'
        ? { ...r, api: [{ token: 'xoxb-1234567890-abcdef' }] }
        : r,
    )
    try {
      expect((await finish(g)).problems).toEqual([
        'privacidad: token de Slack «xoxb-1234567890-abcdef» en mobile-light/runs (API)',
      ])
    } finally {
      await rm(g.root, { recursive: true })
    }
  })

  it('falla si falta una captura, mide mal, sobra otra o el tema no es el de su carpeta', async () => {
    const g = await fakeGallery((r) =>
      r.screen === 'usage' && r.theme === 'dark' && r.viewport === 'mobile'
        ? { ...r, dataTheme: 'light' }
        : r,
    )
    try {
      await rm(join(g.galleryDir, 'desktop/light/02-dashboard.png'))
      await writeFile(join(g.galleryDir, 'mobile/dark/40-not-found.png'), fakePng(390, 900))
      const { problems } = await finish(g)
      expect(problems).toEqual(
        expect.arrayContaining([
          'falta desktop/light/02-dashboard.png',
          'mobile/dark/40-not-found.png mide 390 × 900',
          'mobile-dark/usage: tema light',
        ]),
      )
      expect(await zipExists(g.galleryDir)).toBe(false)
    } finally {
      await rm(g.root, { recursive: true })
    }
  })
})
