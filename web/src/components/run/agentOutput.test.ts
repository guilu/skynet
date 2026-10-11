import { describe, expect, it } from 'vitest'
import { outputSummary } from './agentOutput'

describe('outputSummary', () => {
  it('resume cada campo en una línea', () => {
    expect(outputSummary({ hasBlockingFindings: true, score: 4, extra: null })).toBe(
      'hasBlockingFindings: sí · score: 4 · extra: —',
    )
    expect(outputSummary({ ok: false, items: [1, 2], data: { a: 1 } })).toBe(
      'ok: no · items: 2 elementos · data: {…}',
    )
  })

  it('acorta los textos largos y no enseña nada si no hay objeto', () => {
    expect(outputSummary({ summary: 'x'.repeat(80) })).toBe(`summary: ${'x'.repeat(59)}…`)
    expect(outputSummary(null)).toBe('')
    expect(outputSummary([1])).toBe('')
  })
})
