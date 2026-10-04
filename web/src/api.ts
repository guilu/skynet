// Cliente de la API del control plane. Los tipos reflejan las vistas REST del backend.

export type WorkItemType =
  'FEATURE' | 'BUG' | 'REFACTOR' | 'DEPENDENCY_UPDATE' | 'INCIDENT' | 'SECURITY_REVIEW'

export const WORK_ITEM_TYPES: { value: WorkItemType; label: string }[] = [
  { value: 'FEATURE', label: 'Funcionalidad' },
  { value: 'BUG', label: 'Bug' },
  { value: 'REFACTOR', label: 'Refactoring' },
  { value: 'DEPENDENCY_UPDATE', label: 'Dependencias' },
  { value: 'INCIDENT', label: 'Incidente' },
  { value: 'SECURITY_REVIEW', label: 'Revisión de seguridad' },
]

export interface Project {
  id: string
  key: string
  name: string
  description: string | null
  createdAt: string
}

export interface Repository {
  id: string
  projectId: string
  name: string
  localPath: string
  remoteUrl: string | null
  defaultBranch: string
  createdAt: string
}

export interface WorkItem {
  id: string
  projectId: string
  key: string
  title: string
  description: string | null
  type: WorkItemType
  externalRef: string | null
  status: 'OPEN' | 'CLOSED'
  createdAt: string
}

export interface AgentRun {
  id: string
  stageRunId: string
  repositoryId: string
  kind: 'START' | 'RESUME' | 'RETRY' | 'FORK'
  status: string
  provider: string
  providerSessionId: string | null
  model: string | null
  createdAt: string
  startedAt: string | null
  lastActivityAt: string | null
  finishedAt: string | null
  exitCode: number | null
  numTurns: number | null
  inputTokens: number | null
  outputTokens: number | null
  costUsd: number | null
  error: string | null
}

export interface StageRun {
  id: string
  stageKey: string
  status: string
  attempt: number
  startedAt: string | null
  finishedAt: string | null
  agents: AgentRun[]
}

export interface Run {
  id: string
  workItemId: string
  status: string
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  stages: StageRun[]
}

export interface Prompt {
  id: string
  role: string
  content: string
  sha256: string
  createdAt: string
}

export interface AgentRunDetail {
  agent: AgentRun
  workflowRunId: string
  prompts: Prompt[]
}

export interface StoredEvent {
  sequence: number
  eventId: string
  workflowRunId: string | null
  aggregateType: string
  aggregateId: string
  type: string
  payload: Record<string, unknown>
  occurredAt: string
  recordedAt: string
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const problem = (await res.json()) as { detail?: string; title?: string }
      message = problem.detail ?? problem.title ?? message
    } catch {
      // Respuesta sin cuerpo JSON.
    }
    throw new ApiError(res.status, message)
  }
  return (await res.json()) as T
}

export const api = {
  projects: () => request<Project[]>('GET', '/api/projects'),
  project: (id: string) => request<Project>('GET', `/api/projects/${id}`),
  createProject: (body: { key: string; name: string; description?: string }) =>
    request<Project>('POST', '/api/projects', body),
  repositories: (projectId: string) =>
    request<Repository[]>('GET', `/api/projects/${projectId}/repositories`),
  registerRepository: (
    projectId: string,
    body: { name: string; localPath: string; remoteUrl?: string; defaultBranch?: string },
  ) => request<Repository>('POST', `/api/projects/${projectId}/repositories`, body),
  workItems: (projectId: string) =>
    request<WorkItem[]>('GET', `/api/projects/${projectId}/work-items`),
  workItem: (id: string) => request<WorkItem>('GET', `/api/work-items/${id}`),
  createWorkItem: (
    projectId: string,
    body: { title: string; description?: string; type: WorkItemType },
  ) => request<WorkItem>('POST', `/api/projects/${projectId}/work-items`, body),
  runs: (workItemId: string) => request<Run[]>('GET', `/api/work-items/${workItemId}/runs`),
  run: (id: string) => request<Run>('GET', `/api/workflow-runs/${id}`),
  launchRun: (workItemId: string, body: { repositoryId: string; prompt: string }) =>
    request<Run>('POST', `/api/work-items/${workItemId}/runs`, body),
  agent: (id: string) => request<AgentRunDetail>('GET', `/api/agent-runs/${id}`),
  cancelAgent: (id: string) => request<AgentRunDetail>('POST', `/api/agent-runs/${id}/cancel`),
}
