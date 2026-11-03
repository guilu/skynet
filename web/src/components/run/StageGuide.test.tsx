import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { StageJudge as Judge } from '../../api'
import { StageGuide } from './StageGuide'

const judge: Judge = {
  status: 'FIXING',
  round: 1,
  shownRound: 1,
  maxRounds: 2,
  fix: 'implementar',
  threshold: 3.5,
  score: 3,
  passed: false,
  approvals: 0,
  judges: 1,
  findings: [],
  judgements: [],
  error: 'No pasa la guía de pruebas: Falla 1 paso de la guía',
  refute: 0,
  refuting: 0,
  refuteNote: null,
  guide: {
    paths: ['docs/tests/probar-*.md'],
    guides: ['docs/tests/probar-calc.md'],
    steps: [
      {
        guide: 'docs/tests/probar-calc.md',
        section: '## 1. Tests',
        step: 'Pasan los tests',
        status: 'passed',
        evidence: '3 passed',
      },
      {
        guide: 'docs/tests/probar-calc.md',
        section: '## 1. Tests',
        step: 'Divide entre cero',
        status: 'failed',
        evidence: 'ZeroDivisionError',
      },
      {
        guide: 'docs/tests/probar-calc.md',
        section: '## 4. En GitHub',
        step: 'Abre la PR',
        status: 'skipped',
        evidence: null,
      },
    ],
    summary: 'Falla la división entre cero.',
    note: '1 de 2 pasos pasan · 1 saltado',
  },
}

describe('StageGuide', () => {
  it('enseña la nota y cada paso por sección, con su estado y su evidencia', () => {
    render(<StageGuide judge={judge} />)

    expect(screen.getByText('1 de 2 pasos pasan · 1 saltado')).toBeInTheDocument()
    expect(screen.getByText(/docs\/tests\/probar-calc\.md/)).toBeInTheDocument()
    expect(screen.getByText('1. Tests')).toBeInTheDocument()
    expect(screen.getByText('4. En GitHub')).toBeInTheDocument()
    expect(screen.getByText('Falla')).toBeInTheDocument()
    expect(screen.getByText('Saltado')).toBeInTheDocument()
    expect(screen.getByText('ZeroDivisionError')).toBeInTheDocument()
    expect(screen.getByText('Resumen')).toBeInTheDocument()
  })

  it('antes de la primera ronda dice qué guía busca', () => {
    render(
      <StageGuide
        judge={{
          ...judge,
          status: 'JUDGING',
          guide: { ...judge.guide!, guides: [], steps: [], summary: null, note: null },
        }}
      />,
    )
    expect(screen.getByText(/busca docs\/tests\/probar-\*\.md/)).toBeInTheDocument()
  })
})
