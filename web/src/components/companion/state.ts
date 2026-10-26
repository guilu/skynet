import type { AgentRun, AgentStatus, RunStatus, StageRun } from '../../api'

/** Expresiones de la mascota (plan: `plans/estudio-companion.md`). */
export type CompanionState =
  | 'calm'
  | 'sleeping'
  | 'ready'
  | 'thinking'
  | 'running'
  | 'reviewing'
  | 'success'
  | 'error'
  | 'waiting'
  | 'paused'
  | 'offline'
  | 'cancelled'

/** Nombre y cuándo sale cada expresión, en el orden de la galería de Ajustes. */
export const COMPANION_STATES: { state: CompanionState; label: string; when: string }[] = [
  { state: 'calm', label: 'Reposo', when: 'Todo tranquilo: no hay nada que contar.' },
  { state: 'sleeping', label: 'Inactivo', when: 'No hay nada en marcha ni pendiente.' },
  { state: 'ready', label: 'En espera', when: 'En cola o pendiente de empezar.' },
  { state: 'thinking', label: 'Pensando', when: 'El agente arranca o está pensando.' },
  { state: 'running', label: 'Ejecutando', when: 'El agente usa una herramienta.' },
  { state: 'reviewing', label: 'Revisando', when: 'Skynet verifica el trabajo de la fase.' },
  { state: 'success', label: 'Éxito', when: 'Terminó bien.' },
  { state: 'error', label: 'Error', when: 'Falló (error, timeout, presupuesto…).' },
  { state: 'waiting', label: 'Te necesita', when: 'Espera tu aprobación o tu respuesta.' },
  { state: 'paused', label: 'En pausa', when: 'Espera a un reintento programado o a la cuota.' },
  {
    state: 'offline',
    label: 'Sin conexión',
    when: 'Sin conexión en vivo, o el agente no responde.',
  },
  { state: 'cancelled', label: 'Cancelado', when: 'Se canceló o se omitió.' },
]

export const companionLabel = (state: CompanionState) =>
  COMPANION_STATES.find((s) => s.state === state)?.label ?? state

/**
 * Cuál pesa más cuando hay varias a la vez (una ejecución con fases en paralelo, o varias
 * ejecuciones): lo que te necesita primero, luego los fallos y después el trabajo en curso.
 */
const ACTIVE_PRIORITY: CompanionState[] = [
  'waiting',
  'error',
  'offline',
  'reviewing',
  'running',
  'thinking',
  'paused',
  'ready',
]

/** La de más prioridad entre `states`, o null si ninguna cuenta. */
export function strongest(states: CompanionState[]): CompanionState | null {
  return ACTIVE_PRIORITY.find((s) => states.includes(s)) ?? null
}

const AGENT: Record<AgentStatus, CompanionState> = {
  QUEUED: 'ready',
  STARTING: 'thinking',
  THINKING: 'thinking',
  EXECUTING: 'running',
  WAITING_FOR_INPUT: 'waiting',
  WAITING_FOR_APPROVAL: 'waiting',
  UNRESPONSIVE: 'offline',
  COMPLETED: 'success',
  FAILED: 'error',
  CANCELLED: 'cancelled',
}

export function agentExpression(agent: Pick<AgentRun, 'status'>): CompanionState {
  return AGENT[agent.status]
}

/** La cara de una fase; `now` decide si un reintento programado aún está en pausa. */
export function stageExpression(
  stage: Pick<StageRun, 'status' | 'agents' | 'verifications' | 'notBefore'>,
  now: number = Date.now(),
): CompanionState {
  switch (stage.status) {
    case 'WAITING_FOR_INPUT':
    case 'WAITING_FOR_APPROVAL':
      return 'waiting'
    case 'SUCCEEDED':
      return 'success'
    case 'FAILED':
      return 'error'
    case 'CANCELLED':
    case 'SKIPPED':
      return 'cancelled'
    case 'PENDING':
    case 'READY':
      return stage.notBefore && Date.parse(stage.notBefore) > now ? 'paused' : 'ready'
    case 'STARTING':
      return 'thinking'
    case 'RUNNING': {
      if (stage.verifications.some((v) => v.status === 'QUEUED' || v.status === 'RUNNING'))
        return 'reviewing'
      const active = stage.agents
        .map(agentExpression)
        .filter((s) => s !== 'success' && s !== 'cancelled' && s !== 'error')
      return strongest(active) ?? 'running'
    }
  }
}

/** La cara de una ejecución: la terminal si ha acabado; si no, la más fuerte de sus fases. */
export function runExpression(
  run: { status: RunStatus },
  stages: Pick<StageRun, 'status' | 'agents' | 'verifications' | 'notBefore'>[],
  now: number = Date.now(),
): CompanionState {
  switch (run.status) {
    case 'PENDING':
      return 'ready'
    case 'SUCCEEDED':
      return 'success'
    case 'FAILED':
      return 'error'
    case 'CANCELLED':
      return 'cancelled'
    case 'RUNNING': {
      const faces = stages
        .map((s) => stageExpression(s, now))
        .filter((s) => s !== 'success' && s !== 'cancelled')
      return strongest(faces) ?? 'running'
    }
  }
}

/** Lo que sabe el companion global en un momento dado. */
export interface GlobalSnapshot {
  /** El stream en vivo lleva más de 5 s caído. */
  offline: boolean
  /** Hay un fallo que aún no has visto. */
  unseenError: boolean
  /** Aprobaciones pendientes. */
  pendingApprovals: number
  /** Caras de las ejecuciones en marcha. */
  active: CompanionState[]
  /** Algo terminó bien hace menos de 8 s. */
  recentSuccess: boolean
  /** Minutos sin nada en marcha ni pendiente. */
  idleMinutes: number
}

/** Tras cuántos minutos sin nada se duerme. */
export const SLEEP_AFTER_MINUTES = 5

/** La cara del companion global, por orden de prioridad del plan. */
export function globalExpression(s: GlobalSnapshot): CompanionState {
  if (s.offline) return 'offline'
  if (s.unseenError) return 'error'
  if (s.pendingApprovals > 0) return 'waiting'
  const busy = strongest(s.active.filter((a) => a !== 'error' && a !== 'ready'))
  if (busy) return busy
  if (s.recentSuccess) return 'success'
  if (s.active.length > 0) return 'ready'
  return s.idleMinutes >= SLEEP_AFTER_MINUTES ? 'sleeping' : 'calm'
}
