import { describe, expect, it } from 'vitest'
import { describeEvent, formatDuration, statusTone } from './format'

describe('describeEvent', () => {
  it('resume los cambios de estado', () => {
    expect(
      describeEvent('agent.status.changed', { previousStatus: 'QUEUED', status: 'CANCELLED' }),
    ).toBe('En cola → Cancelada')
  })

  it('usa el tipo para eventos desconocidos', () => {
    expect(describeEvent('agent.raw', {})).toBe('agent.raw')
  })

  it('resume los eventos del agente', () => {
    expect(
      describeEvent('agent.tool.started', { name: 'Bash', input: { command: 'pytest -q' } }),
    ).toBe('Herramienta Bash: pytest -q')
    expect(describeEvent('agent.process.exited', { exitCode: 143, signal: 'SIGTERM' })).toBe(
      'Proceso terminado (código 143, SIGTERM)',
    )
    expect(describeEvent('agent.process.exited', { error: 'Se agotó el tiempo máximo' })).toBe(
      'Proceso terminado: Se agotó el tiempo máximo',
    )
  })
})

describe('statusTone', () => {
  it('clasifica estados', () => {
    expect(statusTone('COMPLETED')).toBe('ok')
    expect(statusTone('FAILED')).toBe('bad')
    expect(statusTone('THINKING')).toBe('active')
    expect(statusTone('UNRESPONSIVE')).toBe('warn')
    expect(statusTone('QUEUED')).toBe('neutral')
  })
})

describe('formatDuration', () => {
  it('escala de segundos a horas', () => {
    expect(formatDuration(null)).toBe('—')
    expect(formatDuration(45_000)).toBe('45 s')
    expect(formatDuration(185_000)).toBe('3 min 05 s')
    expect(formatDuration(3_720_000)).toBe('1 h 02 min')
  })
})
