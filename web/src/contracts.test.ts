// Contrato con el backend: los JSON de fixtures/contracts los genera ContractIT a partir de las
// vistas reales. Cada interfaz de api.ts debe tener exactamente las mismas claves; si el backend
// añade, quita o renombra un campo, este test falla hasta que se actualicen los tipos.
import { describe, expect, it } from 'vitest'
import agentRunDetail from '../../fixtures/contracts/agent-run-detail.json'
import artifacts from '../../fixtures/contracts/artifacts.json'
import conversation from '../../fixtures/contracts/conversation.json'
import dashboardMetrics from '../../fixtures/contracts/dashboard-metrics.json'
import dashboardSummary from '../../fixtures/contracts/dashboard-summary.json'
import runPage from '../../fixtures/contracts/run-page.json'
import runView from '../../fixtures/contracts/run-view.json'
import runners from '../../fixtures/contracts/runners.json'
import storedEvent from '../../fixtures/contracts/stored-event.json'
import verifications from '../../fixtures/contracts/verifications.json'
import workflowDefinitions from '../../fixtures/contracts/workflow-definitions.json'
import type {
  AgentRun,
  AgentRunDetail,
  ArtifactSummary,
  Conversation,
  ConversationMessage,
  ConversationTurn,
  DashboardSummary,
  MetricsBucket,
  Prompt,
  Run,
  RunMetrics,
  RunPage,
  RunTotals,
  Runner,
  StageRun,
  StoredEvent,
  TestTotals,
  UnresponsiveAgent,
  VerificationResult,
  WorkflowDefinition,
  Workspace,
} from './api'

// Record<keyof T, true> obliga a listar todas las claves de T y solo esas.
type Keys<T> = Record<keyof T, true>

const AGENT_RUN: Keys<AgentRun> = {
  id: true,
  stageRunId: true,
  parentAgentRunId: true,
  repositoryId: true,
  kind: true,
  status: true,
  provider: true,
  providerSessionId: true,
  model: true,
  createdAt: true,
  startedAt: true,
  lastActivityAt: true,
  finishedAt: true,
  exitCode: true,
  numTurns: true,
  inputTokens: true,
  outputTokens: true,
  cacheReadTokens: true,
  cacheCreationTokens: true,
  costUsd: true,
  costUsdCumulative: true,
  runnerId: true,
  cancelRequestedAt: true,
  resultSubtype: true,
  error: true,
  lastEventType: true,
  currentTool: true,
  workspace: true,
}
const WORKSPACE: Keys<Workspace> = {
  id: true,
  runnerId: true,
  path: true,
  branch: true,
  baseCommit: true,
  cleanupRequestedAt: true,
  cleanupError: true,
  removedAt: true,
}
const CONVERSATION: Keys<Conversation> = { turns: true }
const CONVERSATION_TURN: Keys<ConversationTurn> = {
  workflowRunId: true,
  agent: true,
  prompt: true,
  messages: true,
}
const CONVERSATION_MESSAGE: Keys<ConversationMessage> = {
  sequence: true,
  occurredAt: true,
  text: true,
}
const STAGE_RUN: Keys<StageRun> = {
  id: true,
  stageKey: true,
  status: true,
  attempt: true,
  startedAt: true,
  finishedAt: true,
  agents: true,
}
const RUN_TOTALS: Keys<RunTotals> = {
  inputTokens: true,
  outputTokens: true,
  cacheReadTokens: true,
  cacheCreationTokens: true,
  costUsd: true,
}
const RUN: Keys<Run> = {
  id: true,
  workItemId: true,
  workItemKey: true,
  workItemTitle: true,
  projectId: true,
  status: true,
  createdAt: true,
  startedAt: true,
  finishedAt: true,
  archivedAt: true,
  currentStageRunId: true,
  currentAgentRunId: true,
  totals: true,
  stages: true,
}
const RUN_PAGE: Keys<RunPage> = { items: true, page: true, size: true, total: true }
const PROMPT: Keys<Prompt> = { id: true, role: true, content: true, sha256: true, createdAt: true }
const AGENT_RUN_DETAIL: Keys<AgentRunDetail> = { agent: true, workflowRunId: true, prompts: true }
const RUNNER: Keys<Runner> = {
  id: true,
  name: true,
  capacity: true,
  activeAgents: true,
  runnerVersion: true,
  providerVersion: true,
  registeredAt: true,
  lastHeartbeatAt: true,
  status: true,
  archivedAt: true,
}
const UNRESPONSIVE_AGENT: Keys<UnresponsiveAgent> = {
  agentRunId: true,
  workflowRunId: true,
  workItemKey: true,
  lastActivityAt: true,
}
const METRICS: Keys<RunMetrics> = {
  since: true,
  until: true,
  bucket: true,
  total: true,
  active: true,
  succeeded: true,
  failed: true,
  cancelled: true,
  medianDurationSeconds: true,
  inputTokens: true,
  outputTokens: true,
  costUsd: true,
  buckets: true,
}
const METRICS_BUCKET: Keys<MetricsBucket> = {
  start: true,
  total: true,
  succeeded: true,
  failed: true,
  cancelled: true,
  costUsd: true,
}
const DASHBOARD: Keys<DashboardSummary> = {
  activeRuns: true,
  activeRunsTotal: true,
  recentFailures: true,
  unresponsiveAgents: true,
  staleRunners: true,
  generatedAt: true,
}
const STORED_EVENT: Keys<StoredEvent> = {
  sequence: true,
  eventId: true,
  workflowRunId: true,
  aggregateType: true,
  aggregateId: true,
  type: true,
  payload: true,
  occurredAt: true,
  recordedAt: true,
}
const WORKFLOW_DEFINITION: Keys<WorkflowDefinition> = {
  id: true,
  key: true,
  version: true,
  sourceYaml: true,
  createdAt: true,
}

const ARTIFACT: Keys<ArtifactSummary> = {
  id: true,
  agentRunId: true,
  verificationRunId: true,
  type: true,
  name: true,
  mediaType: true,
  size: true,
  sha256: true,
  truncated: true,
  metadata: true,
  createdAt: true,
}
const VERIFICATION: Keys<VerificationResult> = {
  id: true,
  agentRunId: true,
  trigger: true,
  status: true,
  command: true,
  exitCode: true,
  signal: true,
  error: true,
  tests: true,
  createdAt: true,
  startedAt: true,
  finishedAt: true,
  live: true,
}
const TEST_TOTALS: Keys<TestTotals> = { total: true, failed: true, errors: true, skipped: true }

const keysOf = (value: object) => Object.keys(value).sort()
const expectShape = (value: object, shape: object) => expect(keysOf(value)).toEqual(keysOf(shape))

function expectAgent(agent: AgentRun) {
  expectShape(agent, AGENT_RUN)
  if (agent.workspace) expectShape(agent.workspace, WORKSPACE)
}

function expectRun(run: Run) {
  expectShape(run, RUN)
  expectShape(run.totals, RUN_TOTALS)
  for (const stage of run.stages) {
    expectShape(stage, STAGE_RUN)
    stage.agents.forEach(expectAgent)
  }
}

describe('contratos con el backend', () => {
  it('ejecución', () => {
    expectRun(runView as Run)
    expect(runView.stages[0].agents).toHaveLength(1)
  })

  it('página de ejecuciones', () => {
    expectShape(runPage, RUN_PAGE)
    ;(runPage.items as Run[]).forEach(expectRun)
  })

  it('detalle de agente', () => {
    expectShape(agentRunDetail, AGENT_RUN_DETAIL)
    expectAgent(agentRunDetail.agent as AgentRun)
    agentRunDetail.prompts.forEach((p) => expectShape(p, PROMPT))
  })

  it('runners', () => {
    expect(runners.map((r) => r.status)).toEqual(['ONLINE', 'STALE'])
    runners.forEach((r) => expectShape(r, RUNNER))
  })

  it('dashboard', () => {
    expectShape(dashboardSummary, DASHBOARD)
    ;(dashboardSummary.activeRuns as Run[]).forEach(expectRun)
    dashboardSummary.unresponsiveAgents.forEach((a) => expectShape(a, UNRESPONSIVE_AGENT))
    dashboardSummary.staleRunners.forEach((r) => expectShape(r, RUNNER))
  })

  it('métricas del dashboard', () => {
    expectShape(dashboardMetrics, METRICS)
    dashboardMetrics.buckets.forEach((b) => expectShape(b, METRICS_BUCKET))
  })

  it('evento', () => {
    expectShape(storedEvent, STORED_EVENT)
  })

  it('conversación', () => {
    expectShape(conversation, CONVERSATION)
    expect(conversation.turns.map((t) => t.agent.kind)).toEqual(['START', 'RESUME'])
    for (const turn of conversation.turns) {
      expectShape(turn, CONVERSATION_TURN)
      expectAgent(turn.agent as AgentRun)
      turn.messages.forEach((m) => expectShape(m, CONVERSATION_MESSAGE))
    }
  })

  it('definiciones de workflow', () => {
    workflowDefinitions.forEach((d) => expectShape(d, WORKFLOW_DEFINITION))
  })
  it('artefactos', () => {
    expect(artifacts.map((a) => a.type)).toEqual(['DIFF', 'TEST_REPORT'])
    artifacts.forEach((a) => expectShape(a, ARTIFACT))
  })

  it('verificaciones', () => {
    verifications.forEach((v) => {
      expectShape(v, VERIFICATION)
      if (v.tests) expectShape(v.tests, TEST_TOTALS)
    })
  })
})
