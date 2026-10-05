import type { AgentRun, Run, StoredEvent } from '../../api'
import { isTerminal } from '../../format'

/**
 * Qué hacer con la vista de la ejecución tras un evento:
 * - `none`: el delta ya está aplicado;
 * - `soon`: hay datos que solo da el backend (tokens), conviene releer sin prisa;
 * - `now`: cambia la estructura o termina algo (coste, duración), hay que releer ya.
 */
export type Refetch = 'none' | 'soon' | 'now'

const STRUCTURAL = ['workflow.started', 'stage.ready', 'agent.spawned']
const FINISHING = ['agent.result', 'agent.process.exited', 'workflow.status.changed']
const WITH_USAGE = ['agent.message.received', 'agent.tool.started']

/**
 * Aplica a la vista de una ejecución el efecto de un evento del stream, sin volver a pedirla.
 * Solo toca campos que el evento fija por completo (estados, herramienta en curso, última
 * actividad), así que reaplicar el histórico al conectar deja la vista igual que el backend. Lo
 * acumulado (tokens) y lo que calcula el backend al terminar se pide con `refetch`.
 */
export function applyEvent(run: Run, event: StoredEvent): { run: Run; refetch: Refetch } {
  const p = event.payload
  if (STRUCTURAL.includes(event.type)) return { run, refetch: 'now' }

  if (event.type === 'stage.status.changed') {
    const stages = run.stages.map((s) =>
      s.id === event.aggregateId ? { ...s, status: p.status as typeof s.status } : s,
    )
    return { run: { ...run, stages }, refetch: isTerminal(String(p.status)) ? 'now' : 'none' }
  }
  if (event.type === 'workflow.status.changed') {
    return { run: { ...run, status: p.status as Run['status'] }, refetch: 'now' }
  }
  if (event.aggregateType !== 'agent_run') return { run, refetch: 'none' }

  const found = run.stages.some((s) => s.agents.some((a) => a.id === event.aggregateId))
  if (!found) return { run, refetch: 'now' }

  let refetch: Refetch = 'none'
  if (FINISHING.includes(event.type)) refetch = 'now'
  else if (WITH_USAGE.includes(event.type) && p.usage != null) refetch = 'soon'
  const patch = (agent: AgentRun): AgentRun => {
    if (event.type === 'agent.status.changed') {
      const status = p.status as AgentRun['status']
      if (isTerminal(status)) refetch = 'now'
      return { ...agent, status, currentTool: isTerminal(status) ? null : agent.currentTool }
    }
    const next: AgentRun = {
      ...agent,
      lastEventType: event.type,
      lastActivityAt:
        agent.lastActivityAt && agent.lastActivityAt > event.occurredAt
          ? agent.lastActivityAt
          : event.occurredAt,
    }
    if (event.type === 'agent.tool.started') next.currentTool = String(p.name ?? '')
    if (event.type === 'agent.tool.completed') next.currentTool = null
    if (event.type === 'agent.session.started' && typeof p.model === 'string') next.model = p.model
    return next
  }
  const stages = run.stages.map((s) => ({
    ...s,
    agents: s.agents.map((a) => (a.id === event.aggregateId ? patch(a) : a)),
  }))
  return { run: { ...run, stages }, refetch }
}
