import { describe, expect, it } from 'vitest'
import runView from '../../../../fixtures/contracts/run-view.json'
import type { Run, StoredEvent } from '../../api'
import {
  GROUP_MS,
  listKeys,
  MAX_TOASTS,
  noticeFor,
  pushToast,
  summarize,
  toastText,
  type Notice,
  type Toast,
} from './notices'

const run = runView as unknown as Run
const NOW = Date.parse('2026-10-24T12:00:00Z')

function event(type: string, payload: Record<string, unknown> = {}, sequence = 1): StoredEvent {
  return {
    sequence,
    eventId: `e${sequence}`,
    workflowRunId: run.id,
    aggregateType: type.split('.')[0],
    aggregateId: run.stages[0].agents[0]?.id ?? run.id,
    type,
    payload,
    occurredAt: new Date(NOW).toISOString(),
    recordedAt: new Date(NOW).toISOString(),
  }
}

describe('noticeFor', () => {
  it.each([
    ['approval.requested', { approvalId: 'a1', stageKey: 'aprobar' }, 'approval', 'Te necesito'],
    ['workflow.started', {}, 'started', 'Ejecutando workflow'],
    ['workflow.status.changed', { status: 'SUCCEEDED' }, 'success', 'Workflow completado'],
    ['workflow.status.changed', { status: 'FAILED' }, 'error', 'Ha ocurrido un error'],
    [
      'agent.status.changed',
      { status: 'WAITING_FOR_INPUT' },
      'input',
      `${run.workItemKey} te pregunta algo`,
    ],
    [
      'agent.status.changed',
      { status: 'UNRESPONSIVE' },
      'unresponsive',
      `El agente de ${run.workItemKey} no responde`,
    ],
    ['agent.rate_limit', { status: 'rejected' }, 'limit', 'Claude Code está al límite'],
    [
      'stage.retry.scheduled',
      { reason: 'quota', provider: 'claude-code', notBefore: '2026-11-02T15:04:00Z' },
      'limit',
      'Pausada por la cuota de Claude Code',
    ],
    [
      'stage.quota.waiting',
      { provider: 'codex', notBefore: '2026-11-02T15:04:00Z' },
      'limit',
      'Pausada por la cuota de Codex',
    ],
    [
      'agent.fallback.started',
      { provider: 'codex', replaces: 'claude-code', resetsAt: '2026-11-02T15:04:00Z' },
      'limit',
      'Codex sustituye a Claude Code',
    ],
  ])('%s %j avisa como %s', (type, payload, kind, title) => {
    const notice = noticeFor(event(type, payload), run, NOW)
    expect(notice).toMatchObject({ kind, title, runKey: run.workItemKey })
  })

  it.each([
    ['agent.status.changed', { status: 'THINKING' }],
    ['agent.status.changed', { status: 'EXECUTING' }],
    ['stage.status.changed', { status: 'RUNNING' }],
    ['workflow.status.changed', { status: 'CANCELLED' }],
    ['agent.rate_limit', { status: 'allowed' }],
    // Sin cuota avisa la fase al ponerse en pausa, con la hora a la que sigue.
    ['agent.rate_limit', { status: 'rejected', blocked: true }],
    ['stage.retry.scheduled', { attempt: 2 }],
    // Cambia de proveedor sin esperar: avisa la reserva al arrancar.
    ['stage.retry.scheduled', { reason: 'quota', provider: 'claude-code', switches: true }],
    ['approval.granted', { approvalId: 'a1' }],
  ])('%s %j no avisa: es un cambio interno', (type, payload) => {
    expect(noticeFor(event(type, payload), run, NOW)).toBeNull()
  })

  it('la aprobación lleva a su página y nombra la fase', () => {
    const n = noticeFor(event('approval.requested', { approvalId: 'a1', stageKey: 'aprobar' }), run)
    expect(n).toMatchObject({
      href: '/approvals/a1',
      action: 'Revisar',
      body: `La fase «aprobar» de ${run.workItemKey} espera tu decisión`,
    })
  })

  it('avisa de un revert de lo que aprobó el juez, pero no de uno marcado a mano (EV-A)', () => {
    expect(noticeFor(event('run.escaped', { origin: 'revert-pr', number: 12 }), run)).toMatchObject(
      {
        kind: 'error',
        title: `Se ha revertido ${run.workItemKey}`,
        body: 'La PR #12 se revirtió en la rama por defecto: escape del juez',
        href: `/runs/${run.id}`,
      },
    )
    expect(noticeFor(event('run.escaped', { origin: 'manual' }), run)).toBeNull()
  })

  it('avisa de una lección propuesta (AP-B)', () => {
    const n = noticeFor(
      event('lesson.proposed', {
        kind: 'prevenir',
        category: 'caso-limite',
        runs: 2,
        repository: 'demo-repo',
      }),
      run,
    )
    expect(n).toMatchObject({
      kind: 'success',
      title: 'He aprendido algo',
      body: 'demo-repo: los jueces han pedido `caso-limite` en 2 ejecuciones',
      href: '/lessons',
      action: 'Revisar',
    })
  })

  it('sin la ejecución usa el principio de su id', () => {
    expect(noticeFor(event('workflow.started'), null)?.runKey).toBe(run.id.slice(0, 8))
  })
})

function notice(kind: Notice['kind'], at: number, runKey = 'DEMO-1'): Notice {
  return {
    id: `${kind}-${at}`,
    kind,
    runKey,
    runId: null,
    approvalId: null,
    title: 't',
    body: runKey,
    href: '/runs/x',
    action: 'Ver',
    at,
    read: false,
  }
}

describe('pushToast', () => {
  it('agrupa los del mismo tipo que llegan en menos de 10 s', () => {
    let toasts: Toast[] = []
    toasts = pushToast(toasts, notice('success', NOW, 'DEMO-9'))
    toasts = pushToast(toasts, notice('success', NOW + 4_000, 'DEMO-11'))
    toasts = pushToast(toasts, notice('success', NOW + 8_000, 'DEMO-12'))
    expect(toasts).toHaveLength(1)
    expect(toastText(toasts[0])).toMatchObject({
      title: '3 ejecuciones completadas',
      body: 'DEMO-9, DEMO-11 y DEMO-12',
      href: '/runs',
    })
  })

  it('pasados 10 s, o con otro tipo, entra una tarjeta nueva', () => {
    let toasts = pushToast([], notice('success', NOW))
    toasts = pushToast(toasts, notice('success', NOW + GROUP_MS))
    toasts = pushToast(toasts, notice('error', NOW + GROUP_MS + 1))
    expect(toasts.map((t) => t.kind)).toEqual(['error', 'success', 'success'])
  })

  it(`como mucho ${MAX_TOASTS} a la vez: sale la más antigua`, () => {
    let toasts: Toast[] = []
    for (const [i, kind] of (['success', 'error', 'started', 'limit'] as const).entries())
      toasts = pushToast(toasts, notice(kind, NOW + i))
    expect(toasts.map((t) => t.kind)).toEqual(['limit', 'started', 'error'])
  })
})

describe('textos', () => {
  it('listKeys no repite y une con «y»', () => {
    expect(listKeys(['A'])).toBe('A')
    expect(listKeys(['A', 'B', 'A'])).toBe('A y B')
  })

  it('summarize cuenta ejecuciones y aprobaciones', () => {
    expect(summarize(0, 0, false)).toBe('Todo al día')
    expect(summarize(1, 0, false)).toBe('1 ejecución activa')
    expect(summarize(2, 1, false)).toBe('2 ejecuciones activas · 1 aprobación pendiente')
    expect(summarize(2, 1, true)).toMatch(/Sin conexión/)
  })
})
