import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FindingView, StageRun } from '../../api'
import { mockFetch, renderAt } from '../../test/render'
import { summary } from './findingFormat'
import { StageFindings } from './StageFindings'

const stage = { id: 's1', status: 'SUCCEEDED', judge: { round: 2 } } as unknown as StageRun

const base: FindingView = {
  id: 'f1',
  runId: 'r1',
  stageRunId: 's1',
  stageKey: 'juzgar',
  round: 2,
  source: 'judge',
  judge: 'juez',
  lens: 'correctness',
  provider: 'claude',
  model: 'opus',
  file: 'src/Calc.java',
  line: 14,
  severity: 'minor',
  category: 'caso-limite',
  text: 'Divide entre cero sin comprobarlo',
  outcome: 'aparcado',
  outcomeBy: 'skynet',
  outcomeReason: 'La fase terminó sin corregirlo',
  outcomeAt: '2026-11-10T07:00:00Z',
  repeats: 1,
  firstSeenId: 'f0',
  firstSeenRunId: 'r0',
  createdAt: '2026-11-10T06:50:00Z',
}

const findings: FindingView[] = [
  base,
  {
    ...base,
    id: 'f2',
    round: 1,
    category: 'otro',
    text: 'Redondea mal los negativos',
    outcome: 'refutado',
    outcomeReason: 'Redondea hacia cero a propósito',
    repeats: 0,
    firstSeenId: null,
    firstSeenRunId: null,
  },
  { ...base, id: 'f3', stageRunId: 'otra', text: 'De otra fase' },
]

describe('El registro de hallazgos', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enseña los hallazgos de la fase con su desenlace, las vueltas y la primera vez', async () => {
    vi.stubGlobal('fetch', vi.fn(mockFetch({ '/api/workflow-runs/r1/findings': findings })))
    renderAt('/', <StageFindings runId="r1" stage={stage} />)

    const section = await screen.findByRole('region', { name: 'Registro de hallazgos' })
    expect(section).toHaveTextContent('2 hallazgos: 1 refutado, 1 aparcado')
    // La clase va delante (AP-A); `otro` no se enseña.
    expect(section).toHaveTextContent(
      'Aparcado Caso límite src/Calc.java:14 Divide entre cero sin comprobarlo',
    )
    expect(section).toHaveTextContent('Refutado src/Calc.java:14 Redondea mal')
    expect(section).toHaveTextContent('(juez · corrección · ronda 2)')
    expect(section).toHaveTextContent('salió 2 veces')
    expect(
      within(section).getByRole('link', { name: 'ya salió en otra ejecución' }),
    ).toHaveAttribute('href', '/runs/r0')
    expect(section).toHaveTextContent(
      'Redondea mal los negativos (juez · corrección) Redondea hacia cero',
    )
    expect(section).not.toHaveTextContent('De otra fase')
    // Solo lo abierto o aparcado se marca; lo refutado no.
    expect(within(section).getAllByRole('button', { name: 'Es intencionado' })).toHaveLength(1)
  })

  it('marca un hallazgo como intencionado con su motivo', async () => {
    const fetch = vi.fn(
      mockFetch({
        '/api/workflow-runs/r1/findings': [base],
        'POST /api/findings/f1/outcome': { ...base, outcome: 'aceptado' },
      }),
    )
    vi.stubGlobal('fetch', fetch)
    renderAt('/', <StageFindings runId="r1" stage={stage} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Es intencionado' }))
    const group = screen.getByRole('group', { name: 'Marcar como intencionado' })
    fireEvent.change(within(group).getByLabelText('Motivo (opcional)'), {
      target: { value: 'Adrede' },
    })
    fireEvent.click(within(group).getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/findings/f1/outcome'),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ outcome: 'aceptado', reason: 'Adrede' }),
        }),
      ),
    )
  })

  it('resume los desenlaces', () => {
    expect(summary([base])).toBe('1 hallazgo: 1 aparcado')
  })
})
