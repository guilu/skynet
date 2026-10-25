import { describe, expect, it } from 'vitest'
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
import { buildZip } from './zip.ts'

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
