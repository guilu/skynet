import { describe, expect, it } from 'vitest'
import runPage from '../../../../fixtures/contracts/run-page.json'
import type { Run, StageRun } from '../../api'
import { bandText, groupExpression, helloText, peekText, successStreak } from './peek'

const NOW = Date.parse('2026-10-05T10:00:00Z')
const running = runPage.items[0] as unknown as Run

function run(more: Partial<Run> = {}): Run {
  return { ...running, ...more }
}

function withStage(more: Partial<StageRun>): Run {
  return run({ stages: [{ ...running.stages[0], ...more }] })
}

describe('Bocadillo de la mascota', () => {
  it('cuenta las aprobaciones cuando espera', () => {
    expect(peekText('waiting', 1)).toBe('1 aprobación te espera')
    expect(peekText('waiting', 3)).toBe('3 aprobaciones te esperan')
    expect(peekText('waiting', 0)).toBe('Te necesito')
  })

  it('anima cuando hay trabajo y avisa de los fallos', () => {
    expect(peekText('running', 0)).toBe('¡Todo en marcha! 🚀')
    expect(peekText('thinking', 0)).toBe('¡Todo en marcha! 🚀')
    expect(peekText('error', 0)).toBe('Algo ha fallado')
    expect(peekText('calm', 0)).toBe('Todo al día')
    expect(peekText('ready', 0)).toBe('En cola')
  })
})

describe('Saludo del dashboard', () => {
  it('resume ejecuciones y aprobaciones', () => {
    expect(helloText(2, 1)).toBe(
      'Hola, soy Skyny. Hay 2 ejecuciones en marcha y 1 aprobación esperándote.',
    )
    expect(helloText(1, 0)).toBe('Hola, soy Skyny. Hay 1 ejecución en marcha.')
    expect(helloText(0, 2)).toBe('Hola, soy Skyny. Hay 2 aprobaciones esperándote.')
  })

  it('propone lanzar algo si no hay nada', () => {
    expect(helloText(0, 0)).toBe('Hola, soy Skyny. No hay nada en marcha: ¿lanzamos algo?')
  })

  it('cambia la invitación con la hora', () => {
    expect(helloText(0, 0, 9)).toBe(
      'Hola, soy Skyny. No hay nada en marcha: ¿empezamos el día lanzando algo?',
    )
    expect(helloText(0, 0, 14)).toBe('Hola, soy Skyny. No hay nada en marcha: ¿lanzamos algo?')
    expect(helloText(0, 0, 23)).toBe(
      'Hola, soy Skyny. No hay nada en marcha: es tarde, ¿lo dejamos para mañana?',
    )
    expect(helloText(0, 0, 5)).toContain('es tarde')
    // Con algo en marcha, la hora no cambia nada.
    expect(helloText(1, 0, 23)).toBe('Hola, soy Skyny. Hay 1 ejecución en marcha.')
  })

  it('cuenta la racha sin fallos desde 3', () => {
    expect(helloText(0, 0, 14, 2)).not.toContain('seguidas')
    expect(helloText(1, 0, 14, 5)).toBe(
      'Hola, soy Skyny. Hay 1 ejecución en marcha. Llevas 5 ejecuciones seguidas sin fallos. 🎉',
    )
  })
})

describe('Racha sin fallos', () => {
  const run = (status: Run['status'], finishedAt: string | null) => ({ status, finishedAt })

  it('cuenta las terminadas bien desde la última hasta un fallo', () => {
    expect(
      successStreak([
        run('SUCCEEDED', '2026-10-26T10:00:00Z'),
        run('FAILED', '2026-10-26T09:00:00Z'),
        run('SUCCEEDED', '2026-10-26T11:00:00Z'),
        run('SUCCEEDED', '2026-10-26T12:00:00Z'),
        run('SUCCEEDED', '2026-10-26T08:00:00Z'),
      ]),
    ).toBe(3)
  })

  it('las canceladas no la cortan y las que siguen en marcha no cuentan', () => {
    expect(
      successStreak([
        run('RUNNING', null),
        run('SUCCEEDED', '2026-10-26T12:00:00Z'),
        run('CANCELLED', '2026-10-26T11:00:00Z'),
        run('SUCCEEDED', '2026-10-26T10:00:00Z'),
      ]),
    ).toBe(2)
    expect(successStreak([run('FAILED', '2026-10-26T12:00:00Z')])).toBe(0)
    expect(successStreak([])).toBe(0)
  })
})

describe('Cara de un grupo de ejecuciones', () => {
  it('sin ejecuciones está en reposo', () => {
    expect(groupExpression([], NOW)).toBe('calm')
  })

  it('toma la cara más fuerte', () => {
    const waiting = withStage({ status: 'WAITING_FOR_APPROVAL', agents: [] })
    expect(groupExpression([running], NOW)).toBe('running')
    expect(groupExpression([running, waiting], NOW)).toBe('waiting')
  })
})

describe('Banda de la ejecución', () => {
  it('dice quién trabaja y con qué herramienta', () => {
    const { title, sub } = bandText(running, running.stages, NOW)
    expect(title).toBe('Claude Code está usando Read')
    expect(sub).toMatch(/^Fase 1 de 1 · /)
  })

  it('una fase que espera tu decisión manda sobre el agente', () => {
    const r = withStage({ name: 'Plan', status: 'WAITING_FOR_APPROVAL' })
    expect(bandText(r, r.stages, NOW).title).toBe('La fase «Plan» espera tu decisión')
  })

  it('cuenta el final de la ejecución', () => {
    const failed = run({
      status: 'FAILED',
      stages: [{ ...running.stages[0], status: 'FAILED' }],
    })
    expect(bandText(failed, failed.stages, NOW).title).toBe('Ha fallado la fase «agent»')
    const done = run({ status: 'SUCCEEDED' })
    expect(bandText(done, done.stages, NOW).title).toBe('Ejecución completada')
  })

  it('añade el coste si lo hay', () => {
    const r = run({ totals: { ...running.totals, costUsd: 0.5 } })
    expect(bandText(r, r.stages, NOW).sub).toContain('US$')
  })
})
