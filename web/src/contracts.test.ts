// Contrato con el backend: los JSON de fixtures/contracts los genera ContractIT a partir de las
// vistas reales. Cada interfaz de api.ts debe tener exactamente las mismas claves; si el backend
// añade, quita o renombra un campo, este test falla hasta que se actualicen los tipos.
import { describe, expect, it } from 'vitest'
import agentRunDetail from '../../fixtures/contracts/agent-run-detail.json'
import approval from '../../fixtures/contracts/approval.json'
import approvals from '../../fixtures/contracts/approvals.json'
import artifacts from '../../fixtures/contracts/artifacts.json'
import conversation from '../../fixtures/contracts/conversation.json'
import dashboardMetrics from '../../fixtures/contracts/dashboard-metrics.json'
import dashboardSummary from '../../fixtures/contracts/dashboard-summary.json'
import effectivePolicy from '../../fixtures/contracts/effective-policy.json'
import runPage from '../../fixtures/contracts/run-page.json'
import runView from '../../fixtures/contracts/run-view.json'
import providers from '../../fixtures/contracts/providers.json'
import runners from '../../fixtures/contracts/runners.json'
import storedEvent from '../../fixtures/contracts/stored-event.json'
import usageAgents from '../../fixtures/contracts/usage-agents.json'
import usageHistory from '../../fixtures/contracts/usage-history.json'
import usageRuns from '../../fixtures/contracts/usage-runs.json'
import usageWindows from '../../fixtures/contracts/usage-windows.json'
import verifications from '../../fixtures/contracts/verifications.json'
import workflowDefinitions from '../../fixtures/contracts/workflow-definitions.json'
import workflowVersion from '../../fixtures/contracts/workflow-version.json'
import workflow from '../../fixtures/contracts/workflow.json'
import workflows from '../../fixtures/contracts/workflows.json'
import gitHubDeliveries from '../../fixtures/contracts/github-deliveries.json'
import gitHubStatus from '../../fixtures/contracts/github-status.json'
import type {
  GitHubApp,
  GitHubInstallation,
  GitHubInstallationRepository,
  GitHubRepositoryLink,
  GitHubStatus,
  WebhookDelivery,
  AgentDefinition,
  AgentUsage,
  QuotaShare,
  QuotaWarning,
  RunUsage,
  UsageHistory,
  UsagePoint,
  UsageWindow,
  UsageWindows,
  ApprovalDecision,
  ApprovalEvidence,
  ApprovalEvidenceAgent,
  ApprovalOrigin,
  ApprovalSummary,
  ApprovalView,
  CheckResult,
  AgentRun,
  AgentRunDetail,
  ArtifactSummary,
  Conversation,
  ConversationMessage,
  ConversationTurn,
  DashboardSummary,
  DefinitionDetail,
  InputDefinition,
  MetricsBucket,
  Problem,
  Prompt,
  Run,
  RunMetrics,
  RunPage,
  RunTotals,
  ProviderInfo,
  ProviderKind,
  ProviderShortage,
  Runner,
  StageDefinition,
  StagePolicy,
  StageRun,
  StoredEvent,
  TestTotals,
  UnresponsiveAgent,
  Validation,
  VerificationCheck,
  VerificationResult,
  VersionView,
  WorkflowDefinition,
  WorkflowModel,
  WorkflowRef,
  WorkflowSummary,
  WorkflowView,
  Workspace,
} from './api'

// Record<keyof T, true> obliga a listar todas las claves de T y solo esas.
type Keys<T> = Record<keyof T, true>

const AGENT_RUN: Keys<AgentRun> = {
  failure: true,
  quota: true,
  simulated: true,
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
  childKey: true,
  structuredOutput: true,
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
  children: true,
  phase: true,
  retry: true,
  notBefore: true,
  timeoutSeconds: true,
  deadline: true,
  timedOutAt: true,
  cancelling: true,
  retriedBy: true,
  id: true,
  stageKey: true,
  name: true,
  agent: true,
  dependsOn: true,
  type: true,
  command: true,
  condition: true,
  status: true,
  attempt: true,
  startedAt: true,
  finishedAt: true,
  agents: true,
  checks: true,
  reads: true,
  verifications: true,
  approval: true,
}
const RUN_TOTALS: Keys<RunTotals> = {
  inputTokens: true,
  outputTokens: true,
  cacheReadTokens: true,
  cacheCreationTokens: true,
  costUsd: true,
  quota: true,
}
const QUOTA_SHARE: Keys<QuotaShare> = {
  provider: true,
  bucket: true,
  window: true,
  share: true,
  method: true,
}
const QUOTA_WARNING: Keys<QuotaWarning> = {
  runnerId: true,
  runnerName: true,
  provider: true,
  providerName: true,
  bucket: true,
  label: true,
  window: true,
  usedFraction: true,
  pace: true,
  resetsAt: true,
  reason: true,
}
const USAGE_WINDOW: Keys<UsageWindow> = {
  provider: true,
  providerName: true,
  bucket: true,
  label: true,
  window: true,
  usedFraction: true,
  resetsAt: true,
  plan: true,
  source: true,
  observedAt: true,
  stale: true,
  pace: true,
  skynet: true,
}
const USAGE_HISTORY: Keys<UsageHistory> = {
  runnerId: true,
  provider: true,
  bucket: true,
  window: true,
  windowStart: true,
  resetsAt: true,
  points: true,
}
const USAGE_POINT: Keys<UsagePoint> = { at: true, usedFraction: true, skynet: true, source: true }
const AGENT_USAGE: Keys<AgentUsage> = {
  workflowKey: true,
  workflowName: true,
  agent: true,
  provider: true,
  providerName: true,
  runs: true,
  succeeded: true,
  fiveHour: true,
  sevenDay: true,
  sevenDayPerRun: true,
  sevenDayPerSuccess: true,
  inputTokens: true,
  outputTokens: true,
}
const RUN_USAGE: Keys<RunUsage> = {
  id: true,
  workItemKey: true,
  workItemTitle: true,
  workflowKey: true,
  workflowName: true,
  status: true,
  createdAt: true,
  quota: true,
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
  cancelRequestedAt: true,
  currentStageRunId: true,
  currentAgentRunId: true,
  totals: true,
  workflow: true,
  stages: true,
}
const WORKFLOW_REF: Keys<WorkflowRef> = { key: true, version: true, name: true }
const STAGE_POLICY: Keys<StagePolicy> = {
  stage: true,
  name: true,
  agent: true,
  allowedTools: true,
  permissionMode: true,
  environment: true,
  maxTurns: true,
  maxBudgetUsd: true,
  timeoutMinutes: true,
  type: true,
  model: true,
  command: true,
  provider: true,
  unsupported: true,
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
  providers: true,
}
const PROVIDER_INFO: Keys<ProviderInfo> = { id: true, version: true, simulated: true }
const PROVIDER_KIND: Keys<ProviderKind> = {
  id: true,
  displayName: true,
  capabilities: true,
  onlineRunners: true,
}
const PROVIDER_SHORTAGE: Keys<ProviderShortage> = {
  provider: true,
  displayName: true,
  queuedAgents: true,
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
  providerShortages: true,
  quotaWarnings: true,
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
  stageRunId: true,
  checks: true,
}
const APPROVAL_SUMMARY: Keys<ApprovalSummary> = {
  id: true,
  workflowRunId: true,
  stageRunId: true,
  stageKey: true,
  round: true,
  action: true,
  risk: true,
  status: true,
  createdAt: true,
  decidedAt: true,
  correctionAgentRunId: true,
  origin: true,
}
const APPROVAL_VIEW: Keys<ApprovalView> = {
  id: true,
  workflowRunId: true,
  stageRunId: true,
  stageKey: true,
  round: true,
  action: true,
  risk: true,
  status: true,
  createdAt: true,
  decidedAt: true,
  correctionAgentRunId: true,
  evidence: true,
  decisions: true,
}
const APPROVAL_ORIGIN: Keys<ApprovalOrigin> = {
  project: true,
  workItem: true,
  repository: true,
  run: true,
  workflow: true,
  stage: true,
}
const APPROVAL_EVIDENCE: Keys<ApprovalEvidence> = {
  origin: true,
  revise: true,
  dependencies: true,
  costUsd: true,
  grants: true,
  consequences: true,
  changesRequested: true,
  pullRequest: true,
}
const APPROVAL_AGENT: Keys<ApprovalEvidenceAgent> = {
  agentRunId: true,
  childKey: true,
  status: true,
  provider: true,
  costUsd: true,
  structuredOutput: true,
  checks: true,
  changes: true,
  artifacts: true,
}
const APPROVAL_DECISION: Keys<ApprovalDecision> = {
  id: true,
  action: true,
  comment: true,
  actor: true,
  createdAt: true,
}
const CHECK_RESULT: Keys<CheckResult> = {
  name: true,
  target: true,
  passed: true,
  reason: true,
  omitted: true,
}
const TEST_TOTALS: Keys<TestTotals> = { total: true, failed: true, errors: true, skipped: true }

const VERSION: Keys<VersionView> = {
  id: true,
  version: true,
  status: true,
  createdAt: true,
  updatedAt: true,
  publishedAt: true,
}
const WORKFLOW_SUMMARY: Keys<WorkflowSummary> = {
  id: true,
  key: true,
  name: true,
  description: true,
  published: true,
  draft: true,
  createdAt: true,
  archivedAt: true,
}
const WORKFLOW_VIEW: Keys<WorkflowView> = { workflow: true, versions: true }
const DEFINITION_DETAIL: Keys<DefinitionDetail> = {
  id: true,
  workflowId: true,
  key: true,
  version: true,
  status: true,
  sourceYaml: true,
  revision: true,
  createdAt: true,
  updatedAt: true,
  publishedAt: true,
  archivedAt: true,
  validation: true,
  definition: true,
}
const VALIDATION: Keys<Validation> = { valid: true, publishable: true, problems: true }
const PROBLEM: Keys<Problem> = {
  severity: true,
  path: true,
  line: true,
  column: true,
  message: true,
}
const WORKFLOW_MODEL: Keys<WorkflowModel> = {
  key: true,
  name: true,
  description: true,
  inputs: true,
  agents: true,
  stages: true,
}
const INPUT: Keys<InputDefinition> = {
  name: true,
  type: true,
  required: true,
  defaultValue: true,
  description: true,
}
const AGENT_DEFINITION: Keys<AgentDefinition> = {
  name: true,
  description: true,
  prompt: true,
  tools: true,
  permissionMode: true,
  maxTurns: true,
  maxBudgetUsd: true,
  timeoutMinutes: true,
  model: true,
  provider: true,
  output: true,
}
const STAGE_DEFINITION: Keys<StageDefinition> = {
  phase: true,
  memory: true,
  pullRequest: true,
  ci: true,
  merge: true,
  retry: true,
  timeoutSeconds: true,
  id: true,
  name: true,
  type: true,
  agent: true,
  prompt: true,
  dependsOn: true,
  workspace: true,
  workspaceFrom: true,
  command: true,
  children: true,
  condition: true,
  outputSchema: true,
  outputSchemaPath: true,
  checks: true,
  approval: true,
}
const VERIFICATION_CHECK: Keys<VerificationCheck> = { name: true, command: true, paths: true }

function expectSummary(summary: WorkflowSummary) {
  expectShape(summary, WORKFLOW_SUMMARY)
  if (summary.published) expectShape(summary.published, VERSION)
  if (summary.draft) expectShape(summary.draft, VERSION)
}

const keysOf = (value: object) => Object.keys(value).sort()
const GITHUB_STATUS: Keys<GitHubStatus> = {
  configured: true,
  missing: true,
  apiUrl: true,
  appId: true,
  app: true,
  error: true,
  checkedAt: true,
  installations: true,
  repositories: true,
}
const GITHUB_APP: Keys<GitHubApp> = { slug: true, name: true, owner: true, htmlUrl: true }
const GITHUB_INSTALLATION: Keys<GitHubInstallation> = {
  id: true,
  account: true,
  accountType: true,
  repositorySelection: true,
  suspendedAt: true,
  syncedAt: true,
  repositories: true,
}
const GITHUB_INSTALLATION_REPOSITORY: Keys<GitHubInstallationRepository> = {
  id: true,
  fullName: true,
  private: true,
}
const GITHUB_REPOSITORY_LINK: Keys<GitHubRepositoryLink> = {
  id: true,
  projectId: true,
  projectKey: true,
  name: true,
  remoteUrl: true,
  fullName: true,
  access: true,
  installationId: true,
  installationAccount: true,
  githubRepositoryId: true,
}
const WEBHOOK_DELIVERY: Keys<WebhookDelivery> = {
  deliveryId: true,
  event: true,
  action: true,
  installationId: true,
  repository: true,
  status: true,
  attempts: true,
  error: true,
  events: true,
  receivedAt: true,
  processedAt: true,
}

const expectShape = (value: object, shape: object) => expect(keysOf(value)).toEqual(keysOf(shape))

function expectAgent(agent: AgentRun) {
  expectShape(agent, AGENT_RUN)
  agent.quota.forEach((q) => expectShape(q, QUOTA_SHARE))
  if (agent.workspace) expectShape(agent.workspace, WORKSPACE)
}

function expectRun(run: Run) {
  expectShape(run, RUN)
  expectShape(run.totals, RUN_TOTALS)
  run.totals.quota.forEach((q) => expectShape(q, QUOTA_SHARE))
  if (run.workflow) expectShape(run.workflow, WORKFLOW_REF)
  for (const stage of run.stages) {
    expectShape(stage, STAGE_RUN)
    stage.agents.forEach(expectAgent)
    stage.checks.forEach((c) => expectShape(c, VERIFICATION_CHECK))
    stage.verifications.forEach((v) => {
      expectShape(v, VERIFICATION)
      v.checks.forEach((c) => expectShape(c, CHECK_RESULT))
    })
  }
}

describe('contratos con el backend', () => {
  it('ejecución', () => {
    expectRun(runView as Run)
    expect(runView.stages[0].agents).toHaveLength(1)
  })

  it('política efectiva por fase', () => {
    ;(effectivePolicy as StagePolicy[]).forEach((p) => expectShape(p, STAGE_POLICY))
    expect(effectivePolicy.map((p) => p.agent)).toEqual(['reviewer', 'agy-reviewer', null, null])
    expect(effectivePolicy.map((p) => p.type)).toEqual(['agent', 'agent', 'agent', 'command'])
    expect(effectivePolicy[1].unsupported).toEqual(['allowedTools', 'maxTurns'])
  })

  it('proveedores', () => {
    ;(providers as ProviderKind[]).forEach((p) => expectShape(p, PROVIDER_KIND))
    expect(providers.map((p) => p.id)).toEqual(['claude-code', 'codex', 'antigravity'])
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
    runners.forEach((r) => r.providers.forEach((p) => expectShape(p, PROVIDER_INFO)))
  })

  it('cuotas de uso', () => {
    const windows = usageWindows as UsageWindows
    windows.runners.forEach((r) => {
      expect(keysOf(r)).toEqual(['id', 'name', 'windows'])
      r.windows.forEach((w) => expectShape(w, USAGE_WINDOW))
    })
    expectShape(usageHistory, USAGE_HISTORY)
    usageHistory.points.forEach((p) => expectShape(p, USAGE_POINT))
    ;(usageAgents as AgentUsage[]).forEach((a) => expectShape(a, AGENT_USAGE))
    ;(usageRuns as RunUsage[]).forEach((r) => {
      expectShape(r, RUN_USAGE)
      r.quota.forEach((q) => expectShape(q, QUOTA_SHARE))
    })
    expect((runView as Run).totals.quota.map((q) => q.window)).toEqual(['five_hour', 'seven_day'])
  })

  it('dashboard', () => {
    expectShape(dashboardSummary, DASHBOARD)
    dashboardSummary.quotaWarnings.forEach((w) => expectShape(w, QUOTA_WARNING))
    ;(dashboardSummary.activeRuns as Run[]).forEach(expectRun)
    dashboardSummary.unresponsiveAgents.forEach((a) => expectShape(a, UNRESPONSIVE_AGENT))
    dashboardSummary.staleRunners.forEach((r) => expectShape(r, RUNNER))
    dashboardSummary.providerShortages.forEach((p) => expectShape(p, PROVIDER_SHORTAGE))
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
  it('workflows', () => {
    ;(workflows as WorkflowSummary[]).forEach(expectSummary)
    expectShape(workflow, WORKFLOW_VIEW)
    expectSummary(workflow.workflow as WorkflowSummary)
    workflow.versions.forEach((v) => expectShape(v, VERSION))
  })

  it('versión de un workflow', () => {
    const detail = workflowVersion as DefinitionDetail
    expectShape(detail, DEFINITION_DETAIL)
    expectShape(detail.validation, VALIDATION)
    expect(detail.validation.problems).not.toHaveLength(0)
    detail.validation.problems.forEach((p) => expectShape(p, PROBLEM))
    const model = detail.definition!
    expectShape(model, WORKFLOW_MODEL)
    model.inputs.forEach((i) => expectShape(i, INPUT))
    model.agents.forEach((a) => expectShape(a, AGENT_DEFINITION))
    model.stages.forEach((s) => expectShape(s, STAGE_DEFINITION))
    expect(model.stages.map((s) => s.type)).toEqual(['agent', 'agent', 'verification'])
    const checks = model.stages[2].checks
    expect(checks.map((c) => c.name)).toEqual(['artifacts', 'tests', 'scope', 'command'])
    checks.forEach((c) => expectShape(c, VERIFICATION_CHECK))
  })

  it('artefactos', () => {
    expect(artifacts.map((a) => a.type)).toEqual(['DIFF', 'TEST_REPORT', 'DECLARED_FILE'])
    artifacts.forEach((a) => expectShape(a, ARTIFACT))
  })

  it('verificaciones', () => {
    verifications.forEach((v) => {
      expectShape(v, VERIFICATION)
      if (v.tests) expectShape(v.tests, TEST_TOTALS)
      v.checks.forEach((c) => expectShape(c, CHECK_RESULT))
    })
    expect(verifications.map((v) => v.trigger)).toEqual(['AUTO', 'STAGE'])
  })

  it('aprobaciones', () => {
    ;(approvals as ApprovalSummary[]).forEach((a) => {
      expectShape(a, APPROVAL_SUMMARY)
      expectShape(a.origin, APPROVAL_ORIGIN)
    })
    const view = approval as ApprovalView
    expectShape(view, APPROVAL_VIEW)
    expectShape(view.evidence, APPROVAL_EVIDENCE)
    expectShape(view.evidence.origin, APPROVAL_ORIGIN)
    view.evidence.dependencies.forEach((d) =>
      d.agents.forEach((a) => {
        expectShape(a, APPROVAL_AGENT)
        a.checks.forEach((c) => expectShape(c, CHECK_RESULT))
      }),
    )
    view.evidence.grants.forEach((p) => expectShape(p, STAGE_POLICY))
    view.decisions.forEach((d) => expectShape(d, APPROVAL_DECISION))
    expect(view.evidence.consequences.requestChanges).not.toBeNull()
  })

  it('GitHub', () => {
    const status = gitHubStatus as GitHubStatus
    expectShape(status, GITHUB_STATUS)
    if (status.app) expectShape(status.app, GITHUB_APP)
    status.installations.forEach((i) => {
      expectShape(i, GITHUB_INSTALLATION)
      i.repositories.forEach((r) => expectShape(r, GITHUB_INSTALLATION_REPOSITORY))
    })
    status.repositories.forEach((r) => expectShape(r, GITHUB_REPOSITORY_LINK))
    expect(status.repositories.map((r) => r.access)).toEqual(['CONNECTED'])
    ;(gitHubDeliveries as WebhookDelivery[]).forEach((d) => expectShape(d, WEBHOOK_DELIVERY))
  })
})
