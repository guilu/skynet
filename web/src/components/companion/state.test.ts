import { describe, expect, it } from 'vitest'
import type { AgentStatus, StageRun, StageStatus, VerificationResult } from '../../api'
import {
  agentExpression,
  globalExpression,
  runExpression,
  stageExpression,
  strongest,
  type CompanionState,
  type GlobalSnapshot,
} from './state'

const NOW = Date.parse('2026-10-24T12:00:00Z')

type StageInput = Pick<StageRun, 'status' | 'agents' | 'verifications' | 'notBefore'>

function stage(status: StageStatus, more: Partial<StageInput> = {}): StageInput {
  return { status, agents: [], verifications: [], notBefore: null, ...more }
}

const agent = (status: AgentStatus) => ({ status }) as StageRun['agents'][number]
const verification = (status: VerificationResult['status']) => ({ status }) as VerificationResult

describe('Expresiones de la mascota', () => {
  it.each<[AgentStatus, CompanionState]>([
    ['QUEUED', 'ready'],
    ['STARTING', 'thinking'],
    ['THINKING', 'thinking'],
    ['EXECUTING', 'running'],
    ['WAITING_FOR_INPUT', 'waiting'],
    ['WAITING_FOR_APPROVAL', 'waiting'],
    ['UNRESPONSIVE', 'offline'],
    ['COMPLETED', 'success'],
    ['FAILED', 'error'],
    ['CANCELLED', 'cancelled'],
  ])('agente %s → %s', (status, expected) => {
    expect(agentExpression({ status })).toBe(expected)
  })

  it.each<[string, StageInput, CompanionState]>([
    ['pendiente', stage('PENDING'), 'ready'],
    ['lista', stage('READY'), 'ready'],
    ['reintento programado', stage('PENDING', { notBefore: '2026-10-24T12:01:00Z' }), 'paused'],
    ['reintento ya vencido', stage('READY', { notBefore: '2026-10-24T11:59:00Z' }), 'ready'],
    ['arrancando', stage('STARTING'), 'thinking'],
    ['en marcha sin agentes', stage('RUNNING'), 'running'],
    ['agente pensando', stage('RUNNING', { agents: [agent('THINKING')] }), 'thinking'],
    [
      'reintento terminado y otro ejecutando',
      stage('RUNNING', { agents: [agent('FAILED'), agent('EXECUTING')] }),
      'running',
    ],
    [
      'verificando',
      stage('RUNNING', { agents: [agent('COMPLETED')], verifications: [verification('RUNNING')] }),
      'reviewing',
    ],
    [
      'verificación terminada',
      stage('RUNNING', { agents: [agent('EXECUTING')], verifications: [verification('PASSED')] }),
      'running',
    ],
    ['espera aprobación (W5)', stage('WAITING_FOR_APPROVAL'), 'waiting'],
    ['espera input', stage('WAITING_FOR_INPUT'), 'waiting'],
    ['terminada', stage('SUCCEEDED'), 'success'],
    ['fallida', stage('FAILED'), 'error'],
    ['cancelada', stage('CANCELLED'), 'cancelled'],
    ['omitida', stage('SKIPPED'), 'cancelled'],
  ])('fase %s', (_, input, expected) => {
    expect(stageExpression(input, NOW)).toBe(expected)
  })

  it.each<[string, Parameters<typeof runExpression>[0]['status'], StageInput[], CompanionState]>([
    ['pendiente', 'PENDING', [], 'ready'],
    ['terminada', 'SUCCEEDED', [stage('SUCCEEDED')], 'success'],
    ['fallida', 'FAILED', [stage('FAILED')], 'error'],
    ['cancelada', 'CANCELLED', [], 'cancelled'],
    ['en marcha', 'RUNNING', [stage('SUCCEEDED'), stage('RUNNING')], 'running'],
    [
      'una rama espera aprobación y otra ejecuta',
      'RUNNING',
      [stage('RUNNING', { agents: [agent('EXECUTING')] }), stage('WAITING_FOR_APPROVAL')],
      'waiting',
    ],
    [
      'una rama paralela falló y otra sigue',
      'RUNNING',
      [stage('FAILED'), stage('RUNNING', { agents: [agent('EXECUTING')] })],
      'error',
    ],
    [
      'verificando antes que pensando',
      'RUNNING',
      [stage('RUNNING', { verifications: [verification('QUEUED')] }), stage('STARTING')],
      'reviewing',
    ],
    ['todas terminadas pero aún RUNNING', 'RUNNING', [stage('SUCCEEDED')], 'running'],
  ])('ejecución %s', (_, status, stages, expected) => {
    expect(runExpression({ status }, stages, NOW)).toBe(expected)
  })

  it('strongest elige por prioridad y null si no hay nada', () => {
    expect(strongest(['ready', 'paused', 'thinking'])).toBe('thinking')
    expect(strongest(['success', 'cancelled'])).toBeNull()
  })

  const quiet: GlobalSnapshot = {
    offline: false,
    unseenError: false,
    pendingApprovals: 0,
    active: [],
    recentSuccess: false,
    idleMinutes: 0,
  }

  it.each<[string, Partial<GlobalSnapshot>, CompanionState]>([
    ['sin nada, recién: reposo', {}, 'calm'],
    ['sin nada, 5 min', { idleMinutes: 5 }, 'sleeping'],
    [
      'sin conexión gana a todo',
      { offline: true, unseenError: true, pendingApprovals: 2 },
      'offline',
    ],
    ['error sin ver gana a aprobaciones', { unseenError: true, pendingApprovals: 1 }, 'error'],
    ['aprobación pendiente', { pendingApprovals: 1, active: ['running'] }, 'waiting'],
    ['agente esperando input', { active: ['running', 'waiting'] }, 'waiting'],
    ['ejecutando', { active: ['thinking', 'running'] }, 'running'],
    ['en pausa', { active: ['paused'] }, 'paused'],
    ['éxito reciente', { recentSuccess: true, idleMinutes: 10 }, 'success'],
    ['trabajo en curso antes que éxito', { recentSuccess: true, active: ['thinking'] }, 'thinking'],
    ['ejecuciones en cola no duermen', { active: ['ready'], idleMinutes: 10 }, 'ready'],
  ])('global: %s', (_, snapshot, expected) => {
    expect(globalExpression({ ...quiet, ...snapshot })).toBe(expected)
  })
})
