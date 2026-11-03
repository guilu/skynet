import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { StageJudge as Judge } from '../../api'
import { StageJudge } from './StageJudge'

const judge: Judge = {
  status: 'FIXING',
  round: 1,
  shownRound: 1,
  maxRounds: 2,
  fix: 'implementar',
  threshold: 3.5,
  score: 2.67,
  passed: false,
  approvals: 0,
  judges: 1,
  findings: [
    {
      file: 'src/Calc.java',
      line: 12,
      severity: 'blocking',
      text: 'Divide entre cero sin comprobarlo',
      judge: 'juez',
    },
  ],
  judgements: [
    {
      judge: 'juez',
      lens: 'correctness',
      agentRunId: 'a1',
      status: 'DONE',
      score: 2.67,
      passed: false,
      criteria: [
        { id: 'resuelve', score: 2, reason: 'No trata el cero', weight: 2, required: true },
        { id: 'tests', score: 4, reason: null, weight: 1, required: false },
      ],
      findings: [],
      summary: 'Le falta el caso del cero.',
      error: null,
    },
  ],
  error: 'Suspende el juez',
  refute: 0,
  refuting: 0,
  refuteNote: null,
  guide: null,
}

describe('StageJudge', () => {
  it('enseña la nota, la de cada criterio y los hallazgos con su fichero', () => {
    render(<StageJudge judge={judge} />)

    expect(screen.getByText('2,67 / 5')).toBeInTheDocument()
    expect(screen.getByText(/· corrección/)).toBeInTheDocument()
    expect(screen.getByText('No trata el cero')).toBeInTheDocument()
    expect(screen.getByText('src/Calc.java:12')).toBeInTheDocument()
    expect(screen.getByText(/Divide entre cero sin comprobarlo/)).toBeInTheDocument()
    expect(screen.getByText('Bloquea')).toBeInTheDocument()
    expect(screen.getByText('Resumen de juez')).toBeInTheDocument()
  })

  it('tacha los hallazgos refutados con el motivo y avisa de los recortes', () => {
    render(
      <StageJudge
        judge={{
          ...judge,
          refute: 2,
          refuteNote: 'Se han intentado refutar 8 de 11 hallazgos que bloquean',
          findings: [
            {
              file: 'src/Calc.java',
              line: 20,
              severity: 'blocking',
              text: 'Redondea mal los negativos',
              judge: 'juez',
              refuted: false,
              refutation: 'Pasa con -0,5',
            },
            {
              file: 'src/Calc.java',
              line: 12,
              severity: 'blocking',
              text: 'Divide entre cero sin comprobarlo',
              judge: 'juez',
              refuted: true,
              refutation: 'Ya se comprueba en la línea 10',
            },
          ],
        }}
      />,
    )

    expect(screen.getByText(/2 escépticos por hallazgo que bloquea/)).toBeInTheDocument()
    expect(screen.getByText(/Se han intentado refutar 8 de 11/)).toBeInTheDocument()
    expect(screen.getByText('Refutado')).toBeInTheDocument()
    expect(screen.getByText(/Divide entre cero sin comprobarlo/).closest('del')).not.toBeNull()
    expect(screen.getByText(/Refutado: Ya se comprueba en la línea 10/)).toBeInTheDocument()
    expect(screen.getByText(/No se pudo refutar: Pasa con -0,5/)).toBeInTheDocument()
  })
})
