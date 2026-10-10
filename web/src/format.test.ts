import { describe, expect, it } from 'vitest'
import { describeEvent, formatDuration, statusTone } from './format'

describe('describeEvent', () => {
  it('describe las fases que mueve el motor', () => {
    expect(describeEvent('stage.pending', { stageKey: 'fix', dependsOn: ['plan?', 'tests'] })).toBe(
      'Fase fix en espera de plan?, tests',
    )
    expect(describeEvent('stage.pending', { stageKey: 'plan', dependsOn: [] })).toBe(
      'Fase plan en espera',
    )
    expect(describeEvent('stage.start.failed', { stageKey: 'fix', error: 'sin prompt' })).toBe(
      'La fase fix no pudo arrancar: sin prompt',
    )
  })

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

  it('resume los eventos de las definiciones y distingue qué se archiva', () => {
    expect(describeEvent('workflow.definition.published', { key: 'revisar', version: 2 })).toBe(
      'revisar v2 publicado',
    )
    expect(describeEvent('workflow.archived', { key: 'revisar' })).toBe(
      'Workflow revisar archivado',
    )
    expect(describeEvent('workflow.archived', { workItemId: 'w1' })).toBe('Ejecución archivada')
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
