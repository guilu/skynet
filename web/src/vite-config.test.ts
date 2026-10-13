import { describe, expect, it } from 'vitest'
import { e2eApiUrl } from '../e2e-config.js'

describe('configuración local E2E', () => {
  it('calcula la URL del API desde el puerto E2E configurado', () => {
    expect(e2eApiUrl('18084')).toBe('http://127.0.0.1:18084')
  })

  it('conserva el puerto 8080 como valor predeterminado', () => {
    expect(e2eApiUrl()).toBe('http://127.0.0.1:8080')
  })
})
