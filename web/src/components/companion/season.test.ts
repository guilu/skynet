import { describe, expect, it } from 'vitest'
import { seasonOf } from './season'

describe('Accesorios de temporada', () => {
  it.each<[string, string, ReturnType<typeof seasonOf>]>([
    ['antes de Halloween', '2026-10-23T12:00:00', null],
    ['empieza Halloween', '2026-10-24T00:00:00', 'halloween'],
    ['Halloween', '2026-10-31T20:00:00', 'halloween'],
    ['acaba Halloween', '2026-11-02T23:59:00', 'halloween'],
    ['después de Halloween', '2026-11-03T00:00:00', null],
    ['antes de Navidad', '2026-12-14T12:00:00', null],
    ['Navidad', '2026-12-25T12:00:00', 'christmas'],
    ['Reyes', '2027-01-06T12:00:00', 'christmas'],
    ['después de Reyes', '2027-01-07T00:00:00', null],
    ['junio', '2026-06-30T12:00:00', null],
    ['julio', '2026-07-01T00:00:00', 'summer'],
    ['agosto', '2026-08-31T23:00:00', 'summer'],
    ['septiembre', '2026-09-01T00:00:00', null],
  ])('%s', (_, date, expected) => {
    expect(seasonOf(new Date(date))).toBe(expected)
  })
})
