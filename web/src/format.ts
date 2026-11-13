const dateTime = new Intl.DateTimeFormat('es-ES', {
  dateStyle: 'short',
  timeStyle: 'medium',
})
const time = new Intl.DateTimeFormat('es-ES', { timeStyle: 'medium' })

export const formatDateTime = (iso: string | null) => (iso ? dateTime.format(new Date(iso)) : '—')
export const formatTime = (iso: string) => time.format(new Date(iso))

const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pendiente',
  READY: 'Lista',
  STARTING: 'Arrancando',
  RUNNING: 'En curso',
  QUEUED: 'En cola',
  THINKING: 'Pensando',
  EXECUTING: 'Ejecutando',
  WAITING_FOR_INPUT: 'Esperando información',
  WAITING_FOR_APPROVAL: 'Esperando aprobación',
  UNRESPONSIVE: 'Sin actividad',
  SUCCEEDED: 'Completada',
  COMPLETED: 'Completado',
  FAILED: 'Fallida',
  CANCELLED: 'Cancelada',
  SKIPPED: 'Omitida',
  OPEN: 'Abierto',
  CLOSED: 'Cerrado',
  ONLINE: 'En línea',
  STALE: 'Sin latido',
  PASSED: 'Pasa',
  ERROR: 'Error',
  INTERRUPTED: 'Sin respuesta',
  CANCELLING: 'Cancelando',
}

export const statusLabel = (status: string) => STATUS_LABELS[status] ?? status

export type StatusTone = 'neutral' | 'active' | 'ok' | 'bad' | 'warn'

export function statusTone(status: string): StatusTone {
  if (['SUCCEEDED', 'COMPLETED', 'ONLINE', 'PASSED'].includes(status)) return 'ok'
  if (['FAILED', 'ERROR'].includes(status)) return 'bad'
  if (
    ['WAITING_FOR_INPUT', 'WAITING_FOR_APPROVAL', 'UNRESPONSIVE', 'STALE', 'CANCELLING'].includes(
      status,
    )
  )
    return 'warn'
  if (['RUNNING', 'STARTING', 'THINKING', 'EXECUTING'].includes(status)) return 'active'
  return 'neutral'
}

const TERMINAL = ['SUCCEEDED', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED']
export const isTerminal = (status: string) => TERMINAL.includes(status)

/** Worktree en el que aún se puede trabajar: preparado, sin eliminar y sin eliminación pedida. */
export const workspaceUsable = (
  workspace: { removedAt: string | null; cleanupRequestedAt: string | null } | null,
) => workspace != null && !workspace.removedAt && !workspace.cleanupRequestedAt

/** Duración legible: «45 s», «3 min 05 s», «1 h 02 min». */
export function formatDuration(ms: number | null): string {
  if (ms == null || ms < 0) return '—'
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) return `${h} h ${String(m).padStart(2, '0')} min`
  if (m > 0) return `${m} min ${String(s).padStart(2, '0')} s`
  return `${s} s`
}

/** Tiempo entre dos instantes; sin fin, hasta `now`. */
export function elapsed(start: string | null, end: string | null, now: number): number | null {
  if (!start) return null
  return (end ? new Date(end).getTime() : now) - new Date(start).getTime()
}

/** Resumen legible de un evento para el timeline. */
export function describeEvent(type: string, payload: Record<string, unknown>): string {
  if (type.startsWith('github.')) return describeGitHub(payload)
  const p = (k: string) => String(payload[k] ?? '')
  switch (type) {
    case 'project.created':
      return payload.importedFrom
        ? `Proyecto ${p('key')} importado desde ${p('importedFrom')}`
        : `Proyecto ${p('key')} creado`
    case 'repository.registered':
      return `Repositorio ${p('name')} registrado (${p('localPath')})`
    case 'workitem.created':
      return `Trabajo ${p('key')} creado: ${p('title')}`
    case 'workflow.started':
      return `Ejecución iniciada (workflow ${p('definition')})`
    case 'stage.pending': {
      const deps = Array.isArray(payload.dependsOn) ? payload.dependsOn.map(String) : []
      return `Fase ${p('stageKey')} en espera${deps.length ? ` de ${deps.join(', ')}` : ''}`
    }
    case 'stage.ready':
      return `Fase ${p('stageKey')} preparada (intento ${p('attempt')})`
    case 'stage.skipped':
      return payload.reason === 'condition-false'
        ? `Fase ${p('stageKey')} omitida: su condición ${p('condition')} es falsa`
        : `Fase ${p('stageKey')} omitida: no se ejecutó una fase de la que depende`
    case 'stage.condition.evaluated':
      return `Condición de ${p('stageKey')}: ${p('condition')} es ${
        payload.result ? 'verdadera' : 'falsa'
      }`
    case 'run.assumptions.collected':
      return `${p('stageKey')} supuso ${
        Number(payload.count) === 1 ? '1 cosa' : `${p('count')} cosas`
      } sin poder comprobarlo`
    case 'run.resumed':
      return `${p('actor')} reintentó la fase ${p('stageKey')} (intento ${p('attempt')})`
    case 'stage.retry.scheduled':
      if (payload.reason === 'quota' && payload.switches)
        return `Fase ${p('stageKey')}: ${providerName(String(payload.provider ?? ''))} se quedó sin cuota; sigue ya con el otro proveedor del agente`
      if (payload.reason === 'quota')
        return `Fase ${p('stageKey')}: ${providerName(String(payload.provider ?? ''))} se quedó sin cuota; sigue cuando se renueve, en ${formatDuration(Number(payload.delaySeconds ?? 0) * 1000)}`
      return `Fase ${p('stageKey')}: se reintenta (intento ${p('attempt')}${
        Number(payload.attempt) <= Number(payload.maxAttempts) ? ` de ${p('maxAttempts')}` : ''
      }) en ${formatDuration(Number(payload.delaySeconds ?? 0) * 1000)}`
    case 'stage.quota.waiting':
      return `Fase ${p('stageKey')}: espera a que se renueve la cuota de ${providerName(String(payload.provider ?? ''))}`
    case 'stage.quota.skipped':
      return `${p('actor')} pidió reintentar ya la fase ${p('stageKey')} sin esperar a la cuota`
    case 'stage.timed.out':
      return `Fase ${p('stageKey')}: se acabó su tiempo (intento ${p('attempt')})`
    case 'workflow.cancel.requested':
      return payload.reason === 'parent-cancelled'
        ? 'Cancelación pedida: se canceló la ejecución que la lanzó'
        : payload.reason === 'pull-request-closed'
          ? 'Cancelación pedida: su PR se cerró sin fusionarla'
          : 'Cancelación pedida'
    case 'agent.cancel.requested':
      return payload.reason === 'stage-timeout'
        ? 'Parada pedida al runner: se acabó el tiempo de su fase'
        : 'Cancelación pedida al runner'
    case 'agent.cancel.forced':
      return 'El runner no confirmó la cancelación: se da por cancelado'
    case 'stage.start.failed':
      return `La fase ${p('stageKey')} no pudo arrancar: ${p('error')}`
    case 'agent.spawned':
      return `Agente ${p('provider')} en cola`
    case 'agent.workspace.ready':
      return payload.review
        ? `Worktree listo en la rama ${p('branch')}, con los criterios de ${p('review')}`
        : `Worktree listo en la rama ${p('branch')}`
    case 'agent.workspace.cleanup.requested':
      return `Eliminación del worktree pedida (${
        payload.trigger === 'retention' ? 'retención' : 'desde la web'
      })`
    case 'agent.workspace.removed':
      return payload.error
        ? `No se pudo eliminar el worktree: ${p('error')}`
        : `Worktree eliminado (${p('path')})`
    case 'agent.session.started':
      return `Sesión iniciada${payload.model ? ` con ${p('model')}` : ''}`
    case 'agent.message.received':
      return `Mensaje: ${truncate(p('text'), 120)}`
    case 'agent.tool.started':
      return `Herramienta ${p('name')}${toolSummary(payload.input)}`
    case 'agent.tool.completed':
      return `Herramienta ${p('name')} ${payload.isError ? 'falló' : 'terminada'}`
    case 'agent.file.changed':
      return `Fichero ${p('path')} ${p('change') === 'create' ? 'creado' : 'modificado'}`
    case 'agent.permission.denied':
      return `Permiso denegado: ${p('toolName')}`
    case 'agent.vcs.changed':
      return `Git: ${p('kind')}${payload.branch ? ` (${p('branch')})` : ''}`
    case 'agent.rate_limit':
      return payload.blocked
        ? `Sin cuota: ${providerName(String(payload.provider ?? ''))} rechazó el turno`
        : `Límite de uso: ${p('status')}`
    case 'agent.quota':
      return describeQuota(payload)
    case 'agent.fallback.started':
      return `Sustituye a ${providerName(String(payload.replaces ?? ''))}, sin cuota: sigue ${providerName(String(payload.provider ?? ''))}${payload.model ? ` (${p('model')})` : ''}`
    case 'agent.result':
      return `Resultado ${p('subtype')} · ${p('numTurns')} turnos${
        payload.costUsdCumulative != null
          ? ` · ${formatCost(Number(payload.costUsdCumulative))}`
          : ''
      }`
    case 'agent.process.exited':
      return payload.error
        ? `Proceso terminado: ${p('error')}`
        : `Proceso terminado (código ${p('exitCode')}${payload.signal ? `, ${p('signal')}` : ''})`
    case 'repository.verification.configured':
      return payload.validationCommand
        ? `Verificación configurada: ${truncate(p('validationCommand'), 100)}`
        : 'Verificación desactivada'
    case 'verification.queued':
      return `Verificación ${p('trigger') === 'MANUAL' ? 'manual' : 'automática'} en cola`
    case 'agent.verification.started':
      return `Verificación iniciada: ${truncate(p('command'), 100)}`
    case 'agent.verification.completed':
      return describeVerification(payload)
    case 'artifact.created':
      return `Artefacto ${p('name')} (${formatBytes(Number(payload.size ?? 0))})${
        payload.truncated ? ', recortado' : ''
      }`
    case 'approval.requested':
      return `${p('stageKey')} pide aprobar «${p('action')}»${
        Number(payload.round) > 1 ? ` (ronda ${p('round')})` : ''
      }`
    case 'approval.granted':
      return payload.auto
        ? `${p('stageKey')} se aprobó sola: riesgo bajo, CI en verde y juzgado`
        : `${p('actor')} aprobó ${p('stageKey')}`
    case 'approval.rejected':
      return `${p('actor')} rechazó ${p('stageKey')}: ${truncate(p('comment'), 120)}`
    case 'approval.changes_requested':
      return `${p('actor')} pidió cambios a ${p('reviseStageKey')}: ${truncate(p('comment'), 120)}`
    case 'approval.commented':
      return `${p('actor')} comentó ${p('stageKey')}: ${truncate(p('comment'), 120)}`
    case 'approval.expired':
      return `La aprobación de ${p('stageKey')} caducó sin decisión`
    case 'approval.cancelled':
      return `Aprobación de ${p('stageKey')} cancelada`
    case 'approval.correction.failed':
      return payload.cancelled
        ? `La corrección de ${p('reviseStageKey')} se canceló`
        : `La corrección de ${p('reviseStageKey')} falló: ${truncate(p('error'), 120)}`
    // La entrega por PR (EN): abrir la PR, esperar la CI y fusionarla.
    case 'stage.github.pr.opened':
      return `${p('stageKey')} abrió la PR #${p('number')} (${p('branch')} → ${p('base')})`
    case 'stage.github.pr.updated':
      return `${p('stageKey')} actualizó la PR #${p('number')} con ${p('headSha').slice(0, 7)}`
    case 'stage.github.pr.failed':
      return `${p('stageKey')} no pudo abrir la PR: ${truncate(p('error'), 120)}`
    case 'stage.github.ci.waiting':
      return `${p('stageKey')} espera la CI de ${p('headSha').slice(0, 7)}${
        Number(payload.round) > 0 ? ` (ronda ${p('round')})` : ''
      }`
    case 'stage.github.ci.passed':
      return payload.warning
        ? `${p('stageKey')}: ${truncate(p('warning'), 120)}`
        : `${p('stageKey')}: la CI pasó en ${p('headSha').slice(0, 7)}`
    case 'stage.github.ci.failed':
      return payload.error
        ? `${p('stageKey')}: ${truncate(p('error'), 120)}`
        : `${p('stageKey')}: la CI falló en ${names(payload.failed)}`
    // El juez de las fases verification (VA-B).
    case 'stage.judge.started':
      return `${p('stageKey')} llama a ${
        Array.isArray(payload.judges) && payload.judges.length === 1
          ? '1 juez'
          : `${Array.isArray(payload.judges) ? payload.judges.length : 0} jueces`
      }${Number(payload.round) > 1 ? ` (ronda ${p('round')})` : ''}`
    case 'stage.risk.assessed':
      return `${p('stageKey')}: ${payload.summary ? p('summary') : `nivel ${p('tier')}`}`
    case 'run.risk.raised':
      return `Riesgo de la ejecución: ${
        payload.from == null ? '' : `de ${p('from')} `
      }a ${p('to')} por ${p('stageKey')}`
    case 'stage.judge.verdict':
      return `${p('stageKey')}: ${Number(payload.score).toLocaleString('es-ES', {
        maximumFractionDigits: 2,
      })} / 5 · ${payload.passed ? 'aprobado' : 'suspenso'}, aprueba ${p('approvals')} de ${p(
        'judges',
      )}${Number(payload.blocking) > 0 ? ` · ${p('blocking')} bloquea` : ''}`
    // Lo que cambió después del juez, al corregir la CI (AU-B).
    case 'stage.judge.delta.started':
      return `${p('stageKey')}: ${
        Array.isArray(payload.judges) && payload.judges.length === 1
          ? '1 juez mira'
          : `${Array.isArray(payload.judges) ? payload.judges.length : 0} jueces miran`
      } lo que cambió desde ${p('judgedSha').slice(0, 7)} (nivel ${p('tier') || '2'})`
    case 'stage.judge.delta.verdict':
      return payload.error
        ? `${p('stageKey')}: no se pudo juzgar el delta: ${truncate(p('error'), 100)}`
        : `${p('stageKey')}: delta ${payload.passed ? 'aprobado' : 'suspendido'} con ${Number(
            payload.score,
          ).toLocaleString('es-ES', { maximumFractionDigits: 2 })} / 5`
    case 'stage.judge.delta.unchanged':
      return `${p('stageKey')}: sin cambios desde el juicio en ${p('judgedSha').slice(0, 7)}`
    case 'stage.judge.delta.skipped':
      return `${p('stageKey')}: lo que cambió desde el juicio es de nivel 0, no se vuelve a juzgar`
    case 'stage.judge.findings.filtered':
      return `${p('stageKey')}: ${
        Number(payload.discarded) === 1
          ? 'se descarta 1 hallazgo ya visto'
          : `se descartan ${p('discarded')} hallazgos ya vistos`
      } antes de los escépticos`
    case 'finding.outcome.set': {
      const where = payload.file ? ` en \`${p('file')}\`` : ''
      const what =
        payload.outcome === 'aceptado'
          ? 'marcó como intencionado'
          : payload.outcome === 'irrelevante'
            ? 'marcó como que no aplica'
            : 'deshizo el desenlace de'
      return `${p('actor')} ${what} un hallazgo${where}`
    }
    case 'lesson.proposed': {
      const what =
        payload.kind === 'prevenir'
          ? `los jueces han pedido \`${p('category')}\` en ${p('runs')} ejecuciones`
          : `se ha dado por bueno \`${p('category')}\` en ${p('runs')} ejecuciones`
      return `Lección ${payload.again ? 'propuesta de nuevo' : 'propuesta'} para ${p('repository')}: ${what}`
    }
    case 'lesson.applied':
      return `${p('actor')} aplicó la lección \`${p('category')}\` de ${p('repository')}${
        payload.kind === 'prevenir' ? ' en la memoria' : ' en los criterios del juez'
      }`
    case 'lesson.dismissed':
      return `${p('actor')} descartó la lección \`${p('category')}\` de ${p('repository')}: ${p('reason')}`
    case 'stage.judge.skipped':
      return `${p('stageKey')}: ${p('note')}`
    case 'stage.judge.delta.refute.started':
      return `${p('stageKey')}: los escépticos revisan ${
        Number(payload.findings) === 1 ? '1 hallazgo' : `${p('findings')} hallazgos`
      } del juez del delta`
    case 'stage.judge.refute.started':
      return `${p('stageKey')}: ${
        Number(payload.skeptics) === 1 ? '1 escéptico' : `${p('skeptics')} escépticos`
      } por hallazgo intentan refutar ${
        Number(payload.findings) === 1 ? '1 hallazgo' : `${p('findings')} hallazgos`
      }${payload.note ? ` (${truncate(p('note'), 80)})` : ''}`
    case 'stage.judge.refuted':
      return `${p('stageKey')}: refutados ${p('refuted')} de ${p('findings')} hallazgos`
    // La guía de pruebas como fase (VA-D).
    case 'stage.guide.started':
      return `${p('stageKey')}: ${p('agent')} ejecuta la guía de pruebas${
        Number(payload.round) > 1 ? ` (ronda ${p('round')})` : ''
      }`
    case 'stage.guide.verdict':
      return `${p('stageKey')}: ${payload.passed ? 'pasa' : 'no pasa'} la guía · ${truncate(
        p('note'),
        100,
      )}`
    case 'stage.judge.fix.started':
      return `${p('fixStageKey')} corrige lo que ${
        payload.guide ? 'falla de la guía' : 'suspendió el juez'
      } (ronda ${p('round')} de ${p('maxRounds')})`
    case 'stage.judge.round.started':
      return `${p('stageKey')} vuelve a ${
        payload.guide ? 'ejecutar la guía' : 'comprobar y juzgar'
      } (ronda ${p('round')})`
    case 'stage.judge.failed':
      return `${p('stageKey')}: ${truncate(p('error'), 120)}`
    case 'stage.github.ci.fix.started':
      return `${p('fixStageKey')} corrige la CI (ronda ${p('round')} de ${p('maxRounds')}): ${names(
        payload.failed,
      )}`
    case 'stage.github.pr.merged':
      return payload.external
        ? `La PR #${p('number')} se fusionó en GitHub`
        : `${p('stageKey')} fusionó la PR #${p('number')}${
            payload.branchDeleted ? ` y borró la rama ${p('branch')}` : ''
          }`
    case 'stage.github.pr.merge.failed':
      return `${p('stageKey')} no fusionó la PR #${p('number')}: ${truncate(p('error'), 120)}`
    case 'workflow.github.pr.closed':
      return `La PR #${p('number')} se cerró en GitHub sin fusionarla: se cancela la ejecución`
    case 'workitem.closed':
      return payload.reason === 'pull-request-merged'
        ? `Trabajo ${p('key')} cerrado: su PR se fusionó`
        : `Trabajo ${p('key')} cerrado`
    case 'agent.status.changed':
    case 'stage.status.changed':
    case 'workflow.status.changed':
      return `${statusLabel(p('previousStatus'))} → ${statusLabel(p('status'))}`
    // La cola por repositorio y los disparadores (DS).
    case 'workflow.queued':
      return `En cola: el repositorio ya tiene ${p('running')} en marcha (límite ${p('limit')})`
    case 'trigger.launched':
      return `${triggerSource(payload)} lanzó ${p('workflow')} en ${p('workItemKey')}`
    case 'trigger.duplicate':
      return `${triggerSource(payload)}: ${p('workItemKey')} ya tenía una ejecución en marcha`
    case 'trigger.rejected':
      return `${triggerSource(payload)} no lanzó nada: ${truncate(p('reason'), 120)}`
    case 'trigger.command.corrige':
      return `${triggerSource(payload)} pidió cambios en ${p('workItemKey')}`
    case 'trigger.command.cancela':
      return `${triggerSource(payload)} canceló ${p('workItemKey')}`
    case 'trigger.command.estado':
      return `${triggerSource(payload)} preguntó el estado de ${p('workItemKey')}`
    case 'trigger.command.rejected':
      return `${triggerSource(payload)}: ${truncate(p('reason'), 120)}`
    case 'trigger.schedule.launched':
      return `Disparador programado (${p('cron')}) lanzó ${p('workflow')}`
    case 'trigger.schedule.skipped':
      return `Disparador programado (${p('cron')}) saltado: la ejecución anterior sigue en marcha`
    case 'trigger.schedule.rejected':
      return `Disparador programado (${p('cron')}) no lanzó: ${truncate(p('reason'), 120)}`
    case 'workflow.definition.created':
      return `Borrador de ${p('key')} v${p('version')} creado`
    case 'workflow.definition.published':
      return `${p('key')} v${p('version')} publicado`
    case 'workflow.definition.discarded':
      return `Borrador de ${p('key')} v${p('version')} descartado`
    // Lo que una ejecución guarda en la memoria: la bitácora o lo que guardan sus agentes.
    case 'memory.created':
      return payload.kind === 'RUN_LOG'
        ? 'Bitácora guardada en la memoria'
        : `Memoria: «${p('title')}» guardada`
    case 'memory.updated':
      return payload.kind === 'RUN_LOG'
        ? 'Bitácora actualizada en la memoria'
        : `Memoria: «${p('title')}» actualizada (v${p('version')})`
    // El mismo tipo vale para un workflow (con `key`) y para una ejecución.
    case 'workflow.archived':
      return payload.key ? `Workflow ${p('key')} archivado` : 'Ejecución archivada'
    case 'workflow.restored':
      return payload.key ? `Workflow ${p('key')} restaurado` : 'Ejecución restaurada'
    default:
      return type
  }
}

/** «@guilu en la issue guilu/demo-repo#7»: quién y desde dónde pidió algo desde GitHub. */
function triggerSource(payload: Record<string, unknown>): string {
  const sender = payload.sender ? `@${String(payload.sender)}` : 'GitHub'
  const command = payload.command ? ` con «${String(payload.command)}»` : ''
  return `${sender} en ${String(payload.source ?? '')}#${String(payload.issue ?? '')}${command}`
}

/** Los nombres de una lista del payload, separados por comas. */
function names(value: unknown): string {
  return Array.isArray(value) ? value.map(String).join(', ') : ''
}

/** Un webhook de GitHub (GH-B): `github.<evento>.<acción>` con el resumen de la entrega. */
function describeGitHub(payload: Record<string, unknown>): string {
  const p = (k: string) => (payload[k] == null ? '' : String(payload[k]))
  const repo = p('repository')
  const action = p('action')
  const short = (sha: string) => sha.slice(0, 7)
  switch (p('event')) {
    case 'pull_request': {
      const what = payload.merged === true && action === 'closed' ? 'fusionada' : action
      return `GitHub ${repo}: PR #${p('number')} ${what} (${p('title')})`
    }
    case 'pull_request_review':
      return `GitHub ${repo}: revisión ${p('reviewState').toLowerCase()} de ${p('reviewer')} en la PR #${p('number')}`
    case 'check_run':
      return `GitHub ${repo}: check ${p('name')} ${p('conclusion') || p('status')} en ${short(p('headSha'))}`
    case 'check_suite':
      return `GitHub ${repo}: checks ${p('conclusion') || p('status')} en ${short(p('headSha'))}`
    case 'status':
      return `GitHub ${repo}: estado ${p('context')} ${p('state')} en ${short(p('sha'))}`
    case 'issues':
      return `GitHub ${repo}: issue #${p('number')} ${action}${p('label') ? ` (${p('label')})` : ''}`
    case 'issue_comment':
      return `GitHub ${repo}: comentario de ${p('commentAuthor')} en la ${
        payload.isPullRequest === true ? 'PR' : 'issue'
      } #${p('number')}`
    default:
      return `GitHub ${repo}: ${p('event')}${action ? ` ${action}` : ''}`
  }
}

function describeVerification(payload: Record<string, unknown>): string {
  if (payload.error) return `Verificación sin completar: ${String(payload.error)}`
  const tests = payload.tests as { total?: number; failed?: number; errors?: number } | undefined
  const summary = tests
    ? ` · ${tests.total ?? 0} tests, ${(tests.failed ?? 0) + (tests.errors ?? 0)} fallidos`
    : ''
  return `Verificación terminada (código ${String(payload.exitCode ?? '?')})${summary}`
}

/** Tamaño legible: «512 B», «3,4 KB», «12,1 MB». */
const QUOTA_WINDOW: Record<string, string> = { five_hour: 'de 5 h', seven_day: 'semanal' }

/** «Cuota de Codex: 23 % de 5 h, 13 % semanal». */
function describeQuota(payload: Record<string, unknown>): string {
  const windows = Array.isArray(payload.windows)
    ? (payload.windows as { window?: string; usedFraction?: number; label?: string }[])
    : []
  const parts = windows
    .filter((w) => typeof w.usedFraction === 'number' && w.window)
    .map(
      (w) =>
        `${w.label ? `${w.label} ` : ''}${Math.round(w.usedFraction! * 100)} % ${
          QUOTA_WINDOW[w.window!] ?? w.window
        }`,
    )
  return `Cuota de ${providerName(String(payload.provider ?? ''))}${
    parts.length > 0 ? `: ${parts.join(', ')}` : ''
  }`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ${units[unit]}`
}

export const formatCost = (usd: number | null) =>
  usd == null ? '—' : `${usd.toLocaleString('es-ES', { maximumFractionDigits: 4 })} US$`

export const formatNumber = (n: number | null) => (n == null ? '—' : n.toLocaleString('es-ES'))

export const truncate = (text: string, max: number) =>
  text.length > max ? `${text.slice(0, max - 1)}…` : text

/** Lo más útil de la entrada de una herramienta: el comando, el fichero o el patrón. */
export function toolSummary(input: unknown): string {
  if (input == null || typeof input !== 'object') return ''
  const i = input as Record<string, unknown>
  const value = i.command ?? i.file_path ?? i.pattern ?? i.path ?? i.url
  return typeof value === 'string' ? `: ${truncate(value, 100)}` : ''
}

/** Cifra corta para las tarjetas: 14.845 → «14,8 k», 2.300.000 → «2,3 M». */
export function formatCompact(n: number | null): string {
  if (n == null) return '—'
  const short = (v: number, unit: string) =>
    `${v.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ${unit}`
  if (Math.abs(n) >= 1_000_000) return short(n / 1_000_000, 'M')
  if (Math.abs(n) >= 10_000) return short(n / 1_000, 'k')
  return n.toLocaleString('es-ES')
}

const PROVIDER_NAMES: Record<string, string> = {
  'claude-code': 'Claude Code',
  codex: 'Codex',
  antigravity: 'Antigravity CLI',
  // Retirado en AR-B: solo sale en el histórico.
  gemini: 'Gemini CLI (retirado)',
}

/** Nombre para mostrar de un proveedor de agentes (el id si no se conoce). */
export function providerName(id: string): string {
  return PROVIDER_NAMES[id] ?? id
}

/** Proveedores de un runner: «Claude Code 2.1.288 · Codex». */
/** Nombres de los proveedores de un runner que usan un CLI simulado (AR-A). */
export function simulatedProviders(providers: { id: string; simulated: boolean }[]): string[] {
  return providers.filter((p) => p.simulated).map((p) => providerName(p.id))
}

export function formatProviders(providers: { id: string; version: string | null }[]): string {
  return providers
    .map((p) => (p.version ? `${providerName(p.id)} ${p.version}` : providerName(p.id)))
    .join(' · ')
}
