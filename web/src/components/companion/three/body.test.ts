import { describe, expect, it } from 'vitest'
import { BANDS, BODY_RADIUS, deform, surfaceAt, type Vec3 } from './body'

const unit = (v: Vec3): Vec3 => {
  const l = Math.hypot(...v)
  return [v[0] / l, v[1] / l, v[2] / l]
}

describe('cuerpo de Skyny 3D', () => {
  it('es más ancho abajo que arriba y más plano de perfil', () => {
    const top = deform([BODY_RADIUS * 0.7, BODY_RADIUS * 0.7, 0])
    const bottom = deform([BODY_RADIUS * 0.7, -BODY_RADIUS * 0.7, 0])
    expect(bottom[0]).toBeGreaterThan(top[0])
    const front = deform([0, 0, BODY_RADIUS])
    const side = deform([BODY_RADIUS, 0, 0])
    expect(front[2]).toBeLessThan(side[0])
  })

  it('apoya cada punto de las bandas en la superficie, en su dirección y por fuera', () => {
    for (const path of BANDS)
      for (const p of path) {
        const hit = surfaceAt(p, 0.012)
        const d = unit(p)
        const h = unit(hit)
        d.forEach((n, i) => expect(h[i]).toBeCloseTo(n, 5))
        // Sin el margen, el punto cae justo sobre el cuerpo deformado.
        const on = surfaceAt(p)
        expect(Math.hypot(...hit) - Math.hypot(...on)).toBeCloseTo(0.012, 6)
      }
  })

  it('el punto de la superficie es la deformación de un punto de la esfera', () => {
    const on = surfaceAt([0.3, 0.2, 0.5])
    // Volver a buscarlo desde su propia dirección da el mismo punto.
    const again = surfaceAt(on)
    on.forEach((n, i) => expect(again[i]).toBeCloseTo(n, 6))
  })
})
