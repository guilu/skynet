import type { AgentRun, Run } from '../../api'

/** Agente al que se refiere la cabecera: el que está en curso o, si no hay, el último. */
export function headlineAgent(run: Run): AgentRun | undefined {
  const agents = run.stages.flatMap((s) => s.agents)
  return agents.find((a) => a.id === run.currentAgentRunId) ?? agents.at(-1)
}
