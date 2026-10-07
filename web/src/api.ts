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
  /** Comando de verificación; sin él no se verifica. */
  validationCommand: string | null
  /** Globs de los informes JUnit XML, relativos al worktree. */
  testReportPaths: string[]
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

export type AgentStatus =
  | 'QUEUED'
  | 'STARTING'
  | 'THINKING'
  | 'EXECUTING'
  | 'WAITING_FOR_INPUT'
  | 'WAITING_FOR_APPROVAL'
  | 'UNRESPONSIVE'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export type StageStatus =
  | 'PENDING'
  | 'READY'
  | 'STARTING'
  | 'RUNNING'
  | 'WAITING_FOR_INPUT'
  | 'WAITING_FOR_APPROVAL'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SKIPPED'

export type RunStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface AgentRun {
  id: string
  stageRunId: string
  parentAgentRunId: string | null
  repositoryId: string
  kind: 'START' | 'RESUME' | 'RETRY' | 'FORK'
  status: AgentStatus
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
  cacheReadTokens: number | null
  cacheCreationTokens: number | null
  costUsd: number | null
  costUsdCumulative: number | null
  runnerId: string | null
  cancelRequestedAt: string | null
  resultSubtype: string | null
  error: string | null
  /** Tipo del último evento del agente, p. ej. `agent.tool.started`. */
  lastEventType: string | null
  /** Herramienta en curso, o null si no hay ninguna. */
  currentTool: string | null
  /** Worktree de la invocación, cuando el runner lo ha preparado. */
  workspace: Workspace | null
}

export interface Workspace {
  id: string
  runnerId: string
  path: string
  branch: string
  baseCommit: string | null
}

/** Invocaciones encadenadas de una sesión, de la primera a la última. */
export interface Conversation {
  turns: ConversationTurn[]
}

export interface ConversationTurn {
  workflowRunId: string
  agent: AgentRun
  prompt: string | null
  messages: ConversationMessage[]
}

export interface ConversationMessage {
  sequence: number
  occurredAt: string
  text: string
}

export interface StageRun {
  id: string
  stageKey: string
  status: StageStatus
  attempt: number
  startedAt: string | null
  finishedAt: string | null
  agents: AgentRun[]
}

export interface RunTotals {
  inputTokens: number | null
  outputTokens: number | null
  cacheReadTokens: number | null
  cacheCreationTokens: number | null
  costUsd: number | null
}

export interface Run {
  id: string
  workItemId: string
  workItemKey: string | null
  workItemTitle: string | null
  projectId: string | null
  status: RunStatus
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  /** Fase y agente que aún no han terminado; null cuando todo ha terminado. */
  currentStageRunId: string | null
  currentAgentRunId: string | null
  totals: RunTotals
  stages: StageRun[]
}

export interface RunPage {
  items: Run[]
  page: number
  size: number
  total: number
}

export interface Runner {
  id: string
  name: string
  capacity: number
  activeAgents: number
  runnerVersion: string | null
  providerVersion: string | null
  registeredAt: string
  lastHeartbeatAt: string | null
  status: 'ONLINE' | 'STALE'
}

export interface UnresponsiveAgent {
  agentRunId: string
  workflowRunId: string
  workItemKey: string | null
  lastActivityAt: string | null
}

export interface DashboardSummary {
  activeRuns: Run[]
  activeRunsTotal: number
  recentFailures: Run[]
  unresponsiveAgents: UnresponsiveAgent[]
  staleRunners: Runner[]
  generatedAt: string
}

export interface WorkflowDefinition {
  id: string
  key: string
  version: number
  sourceYaml: string
  createdAt: string
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

export type ArtifactType =
  'PROMPT' | 'RESULT' | 'LOG' | 'GIT_CHANGES' | 'DIFF' | 'VERIFICATION_LOG' | 'TEST_REPORT'

/** Artefacto de una invocación o de una verificación, sin su contenido. */
export interface ArtifactSummary {
  id: string
  agentRunId: string
  verificationRunId: string | null
  type: ArtifactType
  name: string
  mediaType: string
  size: number
  sha256: string
  /** Se recortó por superar el tamaño máximo. */
  truncated: boolean
  metadata: Record<string, unknown>
  createdAt: string
}

/** Trozo del contenido de un artefacto. */
export interface ArtifactChunk {
  bytes: Uint8Array
  /** Tamaño total del artefacto, en bytes. */
  size: number
  offset: number
}

export type VerificationStatus = 'QUEUED' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR'

export interface TestTotals {
  total: number
  failed: number
  errors: number
  skipped: number
}

/** Verificación del worktree, tal como la comprobó Skynet (no lo que declara el agente). */
export interface VerificationResult {
  id: string
  agentRunId: string
  trigger: 'AUTO' | 'MANUAL'
  status: VerificationStatus
  command: string
  exitCode: number | null
  signal: string | null
  error: string | null
  tests: TestTotals | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  live: boolean
}

/** Lanzamiento de un agente; los límites que falten usan los valores por defecto del servidor. */
export interface LaunchRequest {
  repositoryId: string
  prompt: string
  maxTurns?: number
  maxBudgetUsd?: number
  timeoutMinutes?: number
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

/** Lee `limit` bytes de un artefacto desde `offset` (el servidor sirve 8 MiB como mucho). */
async function artifactChunk(id: string, offset: number, limit: number): Promise<ArtifactChunk> {
  const res = await fetch(`/api/artifacts/${id}/content?offset=${offset}&limit=${limit}`)
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`)
  const bytes = new Uint8Array(await res.arrayBuffer())
  return { bytes, size: Number(res.headers.get('X-Artifact-Size') ?? bytes.length), offset }
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
    body: {
      name: string
      localPath: string
      remoteUrl?: string
      defaultBranch?: string
      validationCommand?: string
      testReportPaths?: string[]
    },
  ) => request<Repository>('POST', `/api/projects/${projectId}/repositories`, body),
  /** Cambia el comando de verificación; sin `testReportPaths` se usan los de Gradle y Maven. */
  configureVerification: (
    projectId: string,
    repositoryId: string,
    body: { validationCommand: string | null; testReportPaths?: string[] },
  ) =>
    request<Repository>(
      'PUT',
      `/api/projects/${projectId}/repositories/${repositoryId}/verification`,
      body,
    ),
  workItems: (projectId: string) =>
    request<WorkItem[]>('GET', `/api/projects/${projectId}/work-items`),
  workItem: (id: string) => request<WorkItem>('GET', `/api/work-items/${id}`),
  createWorkItem: (
    projectId: string,
    body: { title: string; description?: string; type: WorkItemType },
  ) => request<WorkItem>('POST', `/api/projects/${projectId}/work-items`, body),
  runs: (workItemId: string) => request<Run[]>('GET', `/api/work-items/${workItemId}/runs`),
  run: (id: string) => request<Run>('GET', `/api/workflow-runs/${id}`),
  listRuns: (query: { status?: RunStatus[]; projectId?: string; page?: number; size?: number }) => {
    const params = new URLSearchParams()
    query.status?.forEach((s) => params.append('status', s))
    if (query.projectId) params.set('projectId', query.projectId)
    if (query.page !== undefined) params.set('page', String(query.page))
    if (query.size !== undefined) params.set('size', String(query.size))
    return request<RunPage>('GET', `/api/workflow-runs?${params}`)
  },
  launchRun: (workItemId: string, body: LaunchRequest) =>
    request<Run>('POST', `/api/work-items/${workItemId}/runs`, body),
  agent: (id: string) => request<AgentRunDetail>('GET', `/api/agent-runs/${id}`),
  cancelAgent: (id: string) => request<AgentRunDetail>('POST', `/api/agent-runs/${id}/cancel`),
  conversation: (id: string) => request<Conversation>('GET', `/api/agent-runs/${id}/conversation`),
  /** Reanuda la sesión con un mensaje; devuelve la ejecución nueva. */
  sendMessage: (id: string, text: string) =>
    request<Run>('POST', `/api/agent-runs/${id}/messages`, { text }),
  /** Bifurca la sesión con un mensaje; devuelve la ejecución nueva. */
  forkAgent: (id: string, text: string) =>
    request<Run>('POST', `/api/agent-runs/${id}/fork`, { text }),
  /** Repite el lanzamiento con el mismo prompt y límites; devuelve la ejecución nueva. */
  retryAgent: (id: string) => request<Run>('POST', `/api/agent-runs/${id}/retry`),
  artifacts: (agentId: string) =>
    request<ArtifactSummary[]>('GET', `/api/agent-runs/${agentId}/artifacts`),
  artifactChunk,
  /** Verificaciones del agente, la más reciente primero. */
  verifications: (agentId: string) =>
    request<VerificationResult[]>('GET', `/api/agent-runs/${agentId}/verifications`),
  /** Reejecuta solo la verificación del worktree del agente. */
  verify: (agentId: string) =>
    request<VerificationResult>('POST', `/api/agent-runs/${agentId}/verifications`),
  runners: () => request<Runner[]>('GET', '/api/runners'),
  dashboard: () => request<DashboardSummary>('GET', '/api/dashboard'),
  workflowDefinitions: () => request<WorkflowDefinition[]>('GET', '/api/workflow-definitions'),
  event: (sequence: number) => request<StoredEvent>('GET', `/api/events/${sequence}`),
  eventsBefore: (query: { workflowRunId?: string; before: number; limit?: number }) => {
    const params = new URLSearchParams({ before: String(query.before) })
    if (query.workflowRunId) params.set('workflowRunId', query.workflowRunId)
    if (query.limit !== undefined) params.set('limit', String(query.limit))
    return request<StoredEvent[]>('GET', `/api/events?${params}`)
  },
}
