import { useQuery } from '@tanstack/react-query'
import { Navigate, useParams } from 'react-router'
import { api } from '../api'
import { ErrorMessage } from '../components/ErrorMessage'
import { SkynyLoading } from '../components/companion/SkynyLoading'

/** `/agent-runs/{id}`: abre el agente en el inspector de su ejecución. */
export function AgentRedirectPage() {
  const { agentId = '' } = useParams()
  const agent = useQuery({ queryKey: ['agent', agentId], queryFn: () => api.agent(agentId) })
  if (agent.error) return <ErrorMessage error={agent.error} />
  if (!agent.data) return <SkynyLoading label="el agente" />
  return <Navigate replace to={`/runs/${agent.data.workflowRunId}?agent=${agentId}`} />
}
