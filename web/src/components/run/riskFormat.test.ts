import { describe, expect, it } from 'vitest'
import type { Risk } from '../../api'
import { riskReason, riskTitle } from './riskFormat'

describe('riskFormat', () => {
  const risk: Risk = {
    tier: 2,
    summary: 'Nivel 2: `db/migration/V2__x.sql` (ruta)',
    files: 3,
    lines: 40,
    reasons: [
      { path: 'db/migration/V2__x.sql', tier: 2, kind: 'level-2', label: 'ruta' },
      {
        path: 'src/Login.java',
        tier: 2,
        kind: 'level-2',
        label: 'autenticación',
        match: 'BCrypt',
      },
      { path: 'docs/guia.md', tier: 0, kind: 'docs' },
    ],
  }

  it('explains why each file counts', () => {
    expect(riskReason(risk.reasons[1])).toBe('src/Login.java (autenticación: «BCrypt»)')
    expect(riskReason(risk.reasons[2])).toBe('docs/guia.md (documentación)')
  })

  it('lists only the files that set the tier', () => {
    expect(riskTitle(risk)).toBe(
      [
        'Nivel 2: `db/migration/V2__x.sql` (ruta)',
        'db/migration/V2__x.sql (ruta)',
        'src/Login.java (autenticación: «BCrypt»)',
      ].join('\n'),
    )
  })
})
