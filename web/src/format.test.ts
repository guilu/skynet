import { describe, expect, it } from 'vitest'
import { describeEvent, formatCompact, formatDuration, providerName, statusTone } from './format'

describe('describeEvent', () => {
  it('describe la entrega por PR: la PR, la CI y la fusión', () => {
    expect(
      describeEvent('stage.github.pr.opened', {
        stageKey: 'pr',
        number: 12,
        branch: 'skynet/ENG-1',
        base: 'main',
      }),
    ).toBe('pr abrió la PR #12 (skynet/ENG-1 → main)')
    expect(
      describeEvent('stage.github.ci.fix.started', {
        fixStageKey: 'implementar',
        round: 1,
        maxRounds: 2,
        failed: ['test', 'lint'],
      }),
    ).toBe('implementar corrige la CI (ronda 1 de 2): test, lint')
    expect(
      describeEvent('stage.github.pr.merged', {
        stageKey: 'merge',
        number: 12,
        branch: 'skynet/ENG-1',
        branchDeleted: true,
        external: false,
      }),
    ).toBe('merge fusionó la PR #12 y borró la rama skynet/ENG-1')
    expect(describeEvent('stage.github.pr.merged', { number: 12, external: true })).toBe(
      'La PR #12 se fusionó en GitHub',
    )
    expect(
      describeEvent('stage.github.pr.merge.failed', {
        stageKey: 'merge',
        number: 12,
        error: 'La CI ya no está en verde: falló en lint',
      }),
    ).toBe('merge no fusionó la PR #12: La CI ya no está en verde: falló en lint')
    expect(describeEvent('workflow.github.pr.closed', { number: 12 })).toBe(
      'La PR #12 se cerró en GitHub sin fusionarla: se cancela la ejecución',
    )
    expect(describeEvent('workitem.closed', { key: 'ENG-1', reason: 'pull-request-merged' })).toBe(
      'Trabajo ENG-1 cerrado: su PR se fusionó',
    )
  })

  it('describe los webhooks de GitHub con su resumen', () => {
    const base = { repository: 'guilu/demo-repo' }
    expect(
      describeEvent('github.pull_request.opened', {
        ...base,
        event: 'pull_request',
        action: 'opened',
        number: 12,
        title: 'Corrige add()',
      }),
    ).toBe('GitHub guilu/demo-repo: PR #12 opened (Corrige add())')
    expect(
      describeEvent('github.pull_request.closed', {
        ...base,
        event: 'pull_request',
        action: 'closed',
        merged: true,
        number: 12,
        title: 'Corrige add()',
      }),
    ).toBe('GitHub guilu/demo-repo: PR #12 fusionada (Corrige add())')
    expect(
      describeEvent('github.check_run.completed', {
        ...base,
        event: 'check_run',
        name: 'build',
        conclusion: 'failure',
        headSha: '4f1c2a9e0b7d3c5e',
      }),
    ).toBe('GitHub guilu/demo-repo: check build failure en 4f1c2a9')
    expect(
      describeEvent('github.issue_comment.created', {
        ...base,
        event: 'issue_comment',
        number: 3,
        commentAuthor: 'guilu',
        isPullRequest: true,
      }),
    ).toBe('GitHub guilu/demo-repo: comentario de guilu en la PR #3')
    expect(describeEvent('github.push', { ...base, event: 'push' })).toBe(
      'GitHub guilu/demo-repo: push',
    )
  })

  it('describe la cuota y el límite de uso de la cuenta', () => {
    expect(
      describeEvent('agent.quota', {
        provider: 'codex',
        windows: [
          { bucket: 'default', window: 'five_hour', usedFraction: 0.23 },
          { bucket: 'default', window: 'seven_day', usedFraction: 0.13 },
        ],
      }),
    ).toBe('Cuota de Codex: 23 % de 5 h, 13 % semanal')
    expect(describeEvent('agent.rate_limit', { status: 'rejected' })).toBe(
      'Límite de uso: rejected',
    )
  })

  it('describe lo que una ejecución guarda en la memoria', () => {
    expect(describeEvent('memory.created', { kind: 'RUN_LOG', title: 'Ejecución adhoc' })).toBe(
      'Bitácora guardada en la memoria',
    )
    expect(describeEvent('memory.created', { kind: 'DECISION', title: 'Squash' })).toBe(
      'Memoria: «Squash» guardada',
    )
    expect(describeEvent('memory.updated', { kind: 'DECISION', title: 'Squash', version: 2 })).toBe(
      'Memoria: «Squash» actualizada (v2)',
    )
  })

  it('describe las fases que mueve el motor', () => {
    expect(
      describeEvent('stage.retry.scheduled', {
        stageKey: 'implement',
        attempt: 2,
        maxAttempts: 3,
        delaySeconds: 30,
      }),
    ).toBe('Fase implement: se reintenta (intento 2 de 3) en 30 s')
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

  it('explica las condiciones', () => {
    const condition = { stageKey: 'fix', condition: 'review.hasBlockingFindings' }
    expect(describeEvent('stage.condition.evaluated', { ...condition, result: false })).toBe(
      'Condición de fix: review.hasBlockingFindings es falsa',
    )
    expect(describeEvent('stage.skipped', { ...condition, reason: 'condition-false' })).toBe(
      'Fase fix omitida: su condición review.hasBlockingFindings es falsa',
    )
    expect(describeEvent('stage.skipped', { stageKey: 'fix', reason: 'upstream' })).toBe(
      'Fase fix omitida: no se ejecutó una fase de la que depende',
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

describe('formatCompact', () => {
  it('acorta las cifras grandes de las tarjetas', () => {
    expect(formatCompact(null)).toBe('—')
    expect(formatCompact(1200)).toBe('1200')
    expect(formatCompact(14_845)).toBe('14,8 k')
    expect(formatCompact(2_300_000)).toBe('2,3 M')
  })
})

describe('providerName', () => {
  it('marca Gemini CLI como retirado y conoce Antigravity', () => {
    expect(providerName('antigravity')).toBe('Antigravity CLI')
    expect(providerName('gemini')).toBe('Gemini CLI (retirado)')
    expect(providerName('otro')).toBe('otro')
  })
})
