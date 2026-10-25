import type { Run, StageRun } from '../../api'
import { elapsed, formatCost, formatDuration, providerName, statusLabel } from '../../format'
import { headlineAgent } from '../run/headlineAgent'
import { runExpression, strongest, type CompanionState } from './state'

/** El bocadillo de la mascota que asoma en las cabeceras, según su cara. */
export function peekText(state: CompanionState, approvals: number): string {
  switch (state) {
    case 'waiting':
      if (approvals === 0) return 'Te necesito'
      return approvals === 1 ? '1 aprobación te espera' : `${approvals} aprobaciones te esperan`
    case 'error':
      return 'Algo ha fallado'
    case 'offline':
      return 'Reconectando…'
    case 'thinking':
    case 'running':
    case 'reviewing':
      return '¡Todo en marcha! 🚀'
    case 'success':
      return '¡Hecho!'
    case 'paused':
      return 'Esperando un reintento'
    case 'cancelled':
      return 'Cancelado'
    case 'ready':
      return 'Todo al día'
    case 'sleeping':
      return 'Todo tranquilo'
  }
}

/** «Hola, soy Skynet. Hay 2 ejecuciones en marcha y 1 aprobación esperándote.» */
export function helloText(runs: number, approvals: number): string {
  const parts = [
    runs > 0 && `${runs} ${runs === 1 ? 'ejecución en marcha' : 'ejecuciones en marcha'}`,
    approvals > 0 &&
      `${approvals} ${approvals === 1 ? 'aprobación esperándote' : 'aprobaciones esperándote'}`,
  ].filter(Boolean)
  if (parts.length === 0) return 'Hola, soy Skynet. No hay nada en marcha: ¿lanzamos algo?'
  return `Hola, soy Skynet. Hay ${parts.join(' y ')}.`
}

/** La cara de un grupo de ejecuciones (un proyecto): la más fuerte de las que están en marcha. */
export function groupExpression(runs: Run[], now: number = Date.now()): CompanionState {
  return strongest(runs.map((r) => runExpression(r, r.stages, now))) ?? 'ready'
}

/** Lo que cuenta la banda de la ejecución: qué pasa ahora y, debajo, fase, tiempo y coste. */
export function bandText(
  run: Run,
  stages: StageRun[],
  now: number,
): { title: string; sub: string } {
  const index = stages.findIndex((s) => s.id === run.currentStageRunId)
  const stage = index >= 0 ? stages[index] : undefined
  const ms = elapsed(run.startedAt ?? run.createdAt, run.finishedAt, now)
  const sub = [
    stage ? `Fase ${index + 1} de ${stages.length}` : `${stages.length} fases`,
    ms != null && formatDuration(ms),
    run.totals.costUsd != null && formatCost(run.totals.costUsd),
  ]
    .filter(Boolean)
    .join(' · ')
  return { title: bandTitle(run, stages, stage), sub }
}

function bandTitle(run: Run, stages: StageRun[], stage: StageRun | undefined): string {
  const name = (s: StageRun) => `«${s.name ?? s.stageKey}»`
  switch (run.status) {
    case 'SUCCEEDED':
      return 'Ejecución completada'
    case 'CANCELLED':
      return 'Ejecución cancelada'
    case 'FAILED': {
      const failed = stages.find((s) => s.status === 'FAILED')
      return failed ? `Ha fallado la fase ${name(failed)}` : 'La ejecución ha fallado'
    }
    case 'PENDING':
      return 'En cola: empieza enseguida'
  }
  const waiting = stages.find((s) => s.status === 'WAITING_FOR_APPROVAL')
  if (waiting) return `La fase ${name(waiting)} espera tu decisión`
  const agent = headlineAgent(run)
  if (agent && !agent.finishedAt) {
    const who = providerName(agent.provider)
    if (agent.status === 'WAITING_FOR_INPUT') return `${who} te pregunta algo`
    if (agent.status === 'UNRESPONSIVE') return `${who} no responde`
    if (agent.currentTool) return `${who} está usando ${agent.currentTool}`
    if (agent.status === 'EXECUTING') return `${who} está trabajando`
    return `${who} está pensando`
  }
  if (stage?.verifications.some((v) => v.status === 'QUEUED' || v.status === 'RUNNING'))
    return `Verificando la fase ${name(stage)}`
  return stage ? `Fase ${name(stage)}: ${statusLabel(stage.status).toLowerCase()}` : 'En marcha'
}
