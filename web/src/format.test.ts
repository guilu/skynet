import { describe, expect, it } from 'vitest'
import { describeEvent, statusTone } from './format'

describe('describeEvent', () => {
  it('resume los cambios de estado', () => {
    expect(
      describeEvent('agent.status.changed', { previousStatus: 'QUEUED', status: 'CANCELLED' }),
    ).toBe('En cola → Cancelada')
  })

  it('usa el tipo para eventos desconocidos', () => {
    expect(describeEvent('agent.tool.started', {})).toBe('agent.tool.started')
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
