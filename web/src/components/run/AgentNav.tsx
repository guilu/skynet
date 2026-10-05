import type { Run } from '../../api'
import { StatusBadge } from '../StatusBadge'

/** Fases de la ejecución en orden, con sus agentes; elegir uno lo abre en el inspector. */
export function AgentNav({
  run,
  selectedId,
  onSelect,
}: {
  run: Run
  selectedId: string | undefined
  onSelect: (agentId: string) => void
}) {
  return (
    <nav className="run-nav card" aria-label="Fases y agentes">
      <h2>Fases</h2>
      <ol className="stepper">
        {run.stages.map((stage, i) => (
          <li key={stage.id} className={stage.id === run.currentStageRunId ? 'current' : undefined}>
            <p className="stepper-stage">
              <span className="stepper-index" aria-hidden="true">
                {i + 1}
              </span>
              <strong>{stage.stageKey}</strong> <StatusBadge status={stage.status} />
              {stage.attempt > 1 && <span className="muted small"> intento {stage.attempt}</span>}
            </p>
            <ul className="agent-list">
              {stage.agents.map((agent) => (
                <li key={agent.id}>
                  <button
                    type="button"
                    className="agent-link"
                    aria-current={agent.id === selectedId ? 'true' : undefined}
                    onClick={() => onSelect(agent.id)}
                  >
                    <span>
                      {agent.provider} <span className="muted small">{agent.kind}</span>
                    </span>
                    <StatusBadge status={agent.status} />
                    {agent.currentTool && <span className="muted small">{agent.currentTool}</span>}
                  </button>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
    </nav>
  )
}
