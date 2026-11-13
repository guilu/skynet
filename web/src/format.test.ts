import { describe, expect, it } from 'vitest'
import { describeEvent, formatCompact, formatDuration, providerName, statusTone } from './format'

describe('describeEvent', () => {
  it('describes the change risk (AU-A)', () => {
    expect(
      describeEvent('stage.risk.assessed', {
        stageKey: 'implementar',
        tier: 2,
        summary: 'Nivel 2: `db/migration/V2__x.sql` (ruta)',
      }),
    ).toBe('implementar: Nivel 2: `db/migration/V2__x.sql` (ruta)')
    expect(describeEvent('run.risk.raised', { to: 1, stageKey: 'implementar' })).toBe(
      'Riesgo de la ejecución: a 1 por implementar',
    )
    expect(describeEvent('run.risk.raised', { from: 1, to: 2, stageKey: 'corregir' })).toBe(
      'Riesgo de la ejecución: de 1 a 2 por corregir',
    )
    expect(describeEvent('run.assumptions.collected', { stageKey: 'spec', count: 2 })).toBe(
      'spec supuso 2 cosas sin poder comprobarlo',
    )
    expect(describeEvent('run.assumptions.collected', { stageKey: 'spec', count: 1 })).toBe(
      'spec supuso 1 cosa sin poder comprobarlo',
    )
  })

  it('describes the delta after the judge (AU-B)', () => {
    const sha = '5a5a5a5a5a5a5a5a5a5a'
    expect(
      describeEvent('stage.judge.delta.started', {
        stageKey: 'ci',
        judgedSha: sha,
        tier: 1,
        judges: [{ agent: 'juez' }],
      }),
    ).toBe('ci: 1 juez mira lo que cambió desde 5a5a5a5 (nivel 1)')
    expect(
      describeEvent('stage.judge.delta.verdict', { stageKey: 'ci', score: 3.9, passed: true }),
    ).toBe('ci: delta aprobado con 3,9 / 5')
    expect(
      describeEvent('stage.judge.delta.verdict', { stageKey: 'ci', error: 'no terminó bien' }),
    ).toBe('ci: no se pudo juzgar el delta: no terminó bien')
    expect(describeEvent('stage.judge.delta.unchanged', { stageKey: 'ci', judgedSha: sha })).toBe(
      'ci: sin cambios desde el juicio en 5a5a5a5',
    )
    expect(describeEvent('stage.judge.delta.skipped', { stageKey: 'ci' })).toBe(
      'ci: lo que cambió desde el juicio es de nivel 0, no se vuelve a juzgar',
    )
  })

  it('dice si el juez lleva los criterios del repositorio (ES-B)', () => {
    expect(describeEvent('agent.workspace.ready', { branch: 'skynet/tkm-1' })).toBe(
      'Worktree listo en la rama skynet/tkm-1',
    )
    expect(
      describeEvent('agent.workspace.ready', { branch: 'skynet/tkm-1', review: 'REVIEW.md' }),
    ).toBe('Worktree listo en la rama skynet/tkm-1, con los criterios de REVIEW.md')
    // AP-C: los criterios aprendidos del repositorio.
    expect(
      describeEvent('agent.workspace.ready', {
        branch: 'skynet/tkm-1',
        review: 'REVIEW.md',
        learned: 2,
      }),
    ).toBe(
      'Worktree listo en la rama skynet/tkm-1, con los criterios de REVIEW.md y 2 criterios aprendidos',
    )
    expect(describeEvent('agent.workspace.ready', { branch: 'skynet/tkm-1', learned: 1 })).toBe(
      'Worktree listo en la rama skynet/tkm-1, con 1 criterio aprendido',
    )
  })

  it('dice cuántos hallazgos ya vistos se descartan (RH-B)', () => {
    expect(
      describeEvent('stage.judge.findings.filtered', { stageKey: 'juzgar', discarded: 2 }),
    ).toBe('juzgar: se descartan 2 hallazgos ya vistos antes de los escépticos')
    expect(
      describeEvent('stage.judge.findings.filtered', { stageKey: 'juzgar', discarded: 1 }),
    ).toBe('juzgar: se descarta 1 hallazgo ya visto antes de los escépticos')
  })

  it('dice quién decidió el desenlace de un hallazgo (RH-A)', () => {
    expect(
      describeEvent('finding.outcome.set', {
        actor: 'diego',
        outcome: 'aceptado',
        file: 'src/Calc.java',
      }),
    ).toBe('diego marcó como intencionado un hallazgo en `src/Calc.java`')
    expect(describeEvent('finding.outcome.set', { actor: 'diego', outcome: 'irrelevante' })).toBe(
      'diego marcó como que no aplica un hallazgo',
    )
    expect(describeEvent('finding.outcome.set', { actor: 'diego', outcome: 'aparcado' })).toBe(
      'diego deshizo el desenlace de un hallazgo',
    )
  })

  it('cuenta los escapes del juez (EV-A)', () => {
    expect(describeEvent('run.escaped', { origin: 'revert-pr', number: 12, seenRef: '#13' })).toBe(
      'Se revirtió la PR #12 en la rama por defecto con la PR #13: escape del juez',
    )
    expect(
      describeEvent('run.escaped', { origin: 'revert-push', number: 12, seenRef: 'a1b2c3d' }),
    ).toBe('Se revirtió la PR #12 en la rama por defecto con el commit a1b2c3d: escape del juez')
    expect(
      describeEvent('run.escaped', {
        origin: 'manual',
        number: 12,
        actor: 'diego',
        note: 'Rompió la exportación',
      }),
    ).toBe('diego marcó que a la PR #12 se le escapó un fallo: Rompió la exportación')
    expect(describeEvent('run.escape.removed', { actor: 'diego' })).toBe(
      'diego dijo que no fue un fallo del juez y quitó el escape',
    )
  })

  it('cuenta las lecciones propuestas, aplicadas y descartadas (AP-B)', () => {
    expect(
      describeEvent('lesson.proposed', {
        kind: 'prevenir',
        category: 'caso-limite',
        runs: 2,
        repository: 'demo-repo',
      }),
    ).toBe('Lección propuesta para demo-repo: los jueces han pedido `caso-limite` en 2 ejecuciones')
    expect(
      describeEvent('lesson.proposed', {
        kind: 'no-senalar',
        category: 'estilo',
        runs: 5,
        repository: 'demo-repo',
        again: true,
      }),
    ).toBe(
      'Lección propuesta de nuevo para demo-repo: se ha dado por bueno `estilo` en 5 ejecuciones',
    )
    expect(
      describeEvent('lesson.applied', {
        actor: 'diego',
        kind: 'prevenir',
        category: 'caso-limite',
        repository: 'demo-repo',
      }),
    ).toBe('diego aplicó la lección `caso-limite` de demo-repo en la memoria')
    expect(
      describeEvent('lesson.disabled', {
        actor: 'diego',
        category: 'estilo',
        repository: 'demo-repo',
        reason: 'Ya no aplica',
      }),
    ).toBe('diego desactivó la lección `estilo` de demo-repo: Ya no aplica')
    expect(
      describeEvent('lesson.dismissed', {
        actor: 'diego',
        category: 'estilo',
        repository: 'demo-repo',
        reason: 'Sí queremos verlo',
      }),
    ).toBe('diego descartó la lección `estilo` de demo-repo: Sí queremos verlo')
  })

  it('dice cuándo se omite el juez y cuándo se aprueba sola la puerta (AU-C)', () => {
    expect(
      describeEvent('stage.judge.skipped', {
        stageKey: 'juzgar',
        note: 'nivel 0: sin juez (juzga desde el nivel 1)',
      }),
    ).toBe('juzgar: nivel 0: sin juez (juzga desde el nivel 1)')
    expect(describeEvent('stage.judge.delta.refute.started', { stageKey: 'ci', findings: 1 })).toBe(
      'ci: los escépticos revisan 1 hallazgo del juez del delta',
    )
    expect(
      describeEvent('approval.granted', { stageKey: 'aprobar', actor: 'skynet', auto: true }),
    ).toBe('aprobar se aprobó sola: riesgo bajo, CI en verde y juzgado')
    expect(describeEvent('approval.granted', { stageKey: 'aprobar', actor: 'diego' })).toBe(
      'diego aprobó aprobar',
    )
  })

  it('dice desde dónde se importó un proyecto', () => {
    expect(describeEvent('project.created', { key: 'FORMA' })).toBe('Proyecto FORMA creado')
    expect(
      describeEvent('project.created', { key: 'FORMA', importedFrom: '/home/diego/code/forma' }),
    ).toBe('Proyecto FORMA importado desde /home/diego/code/forma')
  })

  it('describe los disparadores y la cola (DS)', () => {
    expect(describeEvent('workflow.queued', { repositoryId: 'r1', running: 2, limit: 2 })).toBe(
      'En cola: el repositorio ya tiene 2 en marcha (límite 2)',
    )
    expect(
      describeEvent('trigger.launched', {
        sender: 'guilu',
        source: 'guilu/demo-repo',
        issue: 7,
        workflow: 'tarea',
        workItemKey: 'DEMO-7',
      }),
    ).toBe('@guilu en guilu/demo-repo#7 lanzó tarea en DEMO-7')
    expect(
      describeEvent('trigger.command.cancela', {
        sender: 'guilu',
        source: 'guilu/demo-repo',
        issue: 12,
        command: '/skynet cancela',
        workItemKey: 'DEMO-7',
      }),
    ).toBe('@guilu en guilu/demo-repo#12 con «/skynet cancela» canceló DEMO-7')
    expect(
      describeEvent('trigger.schedule.skipped', { cron: '0 0 9 * * MON', workflow: 'revisar' }),
    ).toBe('Disparador programado (0 0 9 * * MON) saltado: la ejecución anterior sigue en marcha')
    expect(
      describeEvent('trigger.schedule.rejected', {
        cron: '0 0 9 * * MON',
        reason: 'Los disparadores están apagados',
      }),
    ).toBe('Disparador programado (0 0 9 * * MON) no lanzó: Los disparadores están apagados')
  })

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
      describeEvent('stage.judge.refute.started', {
        stageKey: 'juzgar',
        round: 1,
        findings: 3,
        skeptics: 2,
      }),
    ).toBe('juzgar: 2 escépticos por hallazgo intentan refutar 3 hallazgos')
    expect(
      describeEvent('stage.judge.refuted', { stageKey: 'juzgar', refuted: 1, findings: 3 }),
    ).toBe('juzgar: refutados 1 de 3 hallazgos')
    expect(
      describeEvent('stage.guide.started', { stageKey: 'guia', agent: 'probador', round: 2 }),
    ).toBe('guia: probador ejecuta la guía de pruebas (ronda 2)')
    expect(
      describeEvent('stage.guide.verdict', {
        stageKey: 'guia',
        passed: false,
        note: '2 de 3 pasos pasan',
      }),
    ).toBe('guia: no pasa la guía · 2 de 3 pasos pasan')
    expect(
      describeEvent('stage.judge.fix.started', {
        fixStageKey: 'implementar',
        round: 1,
        maxRounds: 2,
        guide: true,
      }),
    ).toBe('implementar corrige lo que falla de la guía (ronda 1 de 2)')
    expect(
      describeEvent('stage.judge.round.started', { stageKey: 'guia', round: 2, guide: true }),
    ).toBe('guia vuelve a ejecutar la guía (ronda 2)')
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
