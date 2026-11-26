import { describe, expect, it } from 'vitest'
import { PULSE_GAP_MS, pulseFor, throttlePulse } from './pulse'

const ev = (type: string, payload: Record<string, unknown> = {}) => ({ type, payload })

describe('reacciones al vivo de Skyny', () => {
  it('cada evento tiene su reacción', () => {
    expect(pulseFor(ev('agent.tool.started', { name: 'Bash' }))).toBe('tool_call')
    expect(pulseFor(ev('agent.file.changed'))).toBe('file_edited')
    expect(pulseFor(ev('agent.message.received'))).toBe('token_tick')
    expect(pulseFor(ev('chat.message.created'))).toBe('token_tick')
    expect(pulseFor(ev('chat.action.proposed'))).toBe('tool_call')
    expect(pulseFor(ev('chat.action.confirmed'))).toBe('gate_passed')
    expect(pulseFor(ev('agent.status.changed'))).toBeNull()
  })

  it('la verificación distingue si pasa o falla', () => {
    expect(pulseFor(ev('agent.verification.completed', { exitCode: 0 }))).toBe('gate_passed')
    expect(pulseFor(ev('agent.verification.completed', { exitCode: 1 }))).toBe('test_failed')
    expect(
      pulseFor(ev('agent.verification.completed', { exitCode: 0, tests: { failed: 2 } })),
    ).toBe('test_failed')
  })

  it('recorta las ráfagas', () => {
    expect(throttlePulse(null, 1000)).toBe(1000)
    expect(throttlePulse(1000, 1000 + PULSE_GAP_MS - 1)).toBeNull()
    expect(throttlePulse(1000, 1000 + PULSE_GAP_MS)).toBe(1000 + PULSE_GAP_MS)
  })
})
