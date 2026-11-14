// Cliente de la API del control plane. Los tipos reflejan las vistas REST del backend.

import type { IconPack } from './components/icons/pack'

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
  /** Cuándo se archivó; null si no lo está. */
  archivedAt: string | null
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
  /** Rutas que ningún agente puede cambiar (VA-A), globs relativos a la raíz. */
  protectedPaths: string[]
  /** Rutas que suben un cambio a riesgo 2 (AU-A), además de las de siempre. */
  riskPaths: string[]
  /** Expresiones regulares que suben a riesgo 2 una línea añadida en código (AU-A). */
  riskPatterns: string[]
  /** Si las puertas con `autoApprove` se aprueban solas (AU-C). Viene apagado. */
  autoMerge?: boolean
  /** Política efectiva de los agentes: la propia o, sin ella, la global. */
  agentPolicy: AgentPolicy
  /** Si el repositorio tiene política propia. */
  agentPolicyCustom: boolean
  createdAt: string
  /** Cuándo se archivó; null si no lo está. */
  archivedAt: string | null
}

/** Lo que pueden usar y gastar los agentes de un repositorio. */
export interface AgentPolicy {
  /** Herramientas permitidas, p. ej. `Bash(git:*)`; con `dontAsk` el resto se deniega. */
  allowedTools: string[]
  permissionMode: PermissionMode
  /** Variables extra que recibe el agente, dentro de las que permite el runner; `null`: todas. */
  environment: string[] | null
  /** Máximos de cada lanzamiento; `null`: sin límite. */
  maxTurns: number | null
  maxBudgetUsd: number | null
  timeoutMinutes: number | null
}

export type PermissionMode = 'dontAsk' | 'acceptEdits' | 'default' | 'plan'

export const PERMISSION_MODES: { value: PermissionMode; label: string }[] = [
  { value: 'dontAsk', label: 'dontAsk: deniega lo que no esté permitido' },
  { value: 'acceptEdits', label: 'acceptEdits: además acepta editar archivos' },
  { value: 'default', label: 'default: pide permiso (sin nadie que responda, se deniega)' },
  { value: 'plan', label: 'plan: solo planifica, no cambia nada' },
]

export interface WorkItem {
  id: string
  projectId: string
  key: string
  title: string
  description: string | null
  type: WorkItemType
  externalRef: string | null
  status: 'OPEN' | 'CLOSED'
  /** Repositorio en el que se trabaja (FS-B); null si aún no se sabe. */
  repositoryId: string | null
  /** Fase derivada de sus intentos, aprobaciones y PRs; null si está archivado. */
  phase: WorkPhase | null
  createdAt: string
  /** Cuándo se archivó; null si no lo está. */
  archivedAt: string | null
}

/**
 * Fase de un trabajo (FS-B), calculada al leer: sin empezar, en marcha, esperándote, atascado
 * (falló el último intento), PR en revisión o entregado.
 */
export type WorkPhase = 'TODO' | 'RUNNING' | 'WAITING' | 'STUCK' | 'REVIEW' | 'DONE'

/** Un trabajo visto desde la fábrica: fase, intentos, coste total, último intento y PR. */
export interface WorkItemSummary {
  id: string
  projectId: string
  projectKey: string
  projectName: string
  key: string
  title: string
  type: WorkItemType
  status: 'OPEN' | 'CLOSED'
  repositoryId: string | null
  repositoryName: string | null
  phase: WorkPhase
  /** Ejecuciones no archivadas del trabajo. */
  attempts: number
  /** Suma de todos sus intentos; null si ningún agente informó del coste. */
  costUsd: number | null
  lastRunId: string | null
  lastRunStatus: RunStatus | null
  workflowKey: string | null
  /** La PR abierta o, si no hay, la última fusionada. */
  pullRequest: PullRequestRef | null
  createdAt: string
  lastActivityAt: string
}

export interface PullRequestRef {
  number: number | null
  url: string | null
  status: 'OPEN' | 'MERGED'
}

/** Centro de operaciones de un proyecto (GET /api/projects/{id}/overview). */
export interface ProjectOverview {
  projectId: string
  /** Desde cuándo cuentan los costes (30 días). */
  since: string
  openWorkItems: number
  activeRuns: number
  openPullRequests: number
  mergedPullRequests: number
  pendingApprovals: number
  costUsd: number | null
  repositories: RepositoryOverview[]
  workItems: WorkItemSummary[]
}

export interface RepositoryOverview {
  id: string
  name: string
  defaultBranch: string
  openWorkItems: number
  activeRuns: number
  openPullRequests: number
  costUsd: number | null
}

/** Una PR producida por la fábrica, con su trabajo y su intento. */
export interface ChangeView {
  id: string
  number: number | null
  url: string | null
  title: string
  status: 'OPEN' | 'MERGED' | 'CLOSED'
  branch: string
  repositoryId: string
  repositoryName: string
  projectId: string
  projectKey: string
  projectName: string
  workItemId: string
  workItemKey: string
  workItemTitle: string
  workflowRunId: string
  createdAt: string
  updatedAt: string
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
  /** Por qué falló (W6), o null si no falló. Decide si la fase se reintenta. */
  failure: AgentFailure | null
  /** Parte de las ventanas de cuota de su CLI que se le atribuye (CU-B). */
  quota: QuotaShare[]
  /** Se ejecutó con un CLI simulado (fake-claude, fake-codex…), no con el real (AR-A). */
  simulated: boolean
  /** El proveedor sin cuota al que sustituye como su reserva (CQ-B), o null. */
  fallback: AgentFallback | null
  /** Tipo del último evento del agente, p. ej. `agent.tool.started`. */
  lastEventType: string | null
  /** Herramienta en curso, o null si no hay ninguna. */
  currentTool: string | null
  /** Worktree de la invocación, cuando el runner lo ha preparado. */
  workspace: Workspace | null
  /** Hijo de una fase `parallel` que ejecuta, o null en las demás fases. */
  childKey: string | null
  /** Salida estructurada que devolvió el agente según su `output`, o null. */
  structuredOutput: unknown
}

/** Por qué falló un agente: `lost`, `start`, `error`, `timeout`, `budget` o `verification`. */
export type AgentFailure =
  'lost' | 'start' | 'error' | 'timeout' | 'budget' | 'verification' | 'quota'

/** `retry` de una fase o de un hijo: intentos en total, qué fallos y la primera espera. */
export interface RetryPolicy {
  maxAttempts: number
  on: AgentFailure[]
  delaySeconds: number
}

export interface Workspace {
  id: string
  runnerId: string
  path: string
  branch: string
  baseCommit: string | null
  /** Se ha pedido eliminarlo y el runner aún no ha respondido. */
  cleanupRequestedAt: string | null
  /** Por qué falló el último intento de eliminarlo. */
  cleanupError: string | null
  /** El runner lo eliminó: ya no se puede continuar, bifurcar ni verificar. La rama sigue. */
  removedAt: string | null
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

/** Dependencia de una fase; una opcional también se cumple si esa fase se omite. */
export interface StageDependency {
  stage: string
  optional: boolean
}

export interface StageRun {
  id: string
  stageKey: string
  /** Nombre de la fase en el YAML. */
  name: string | null
  /** Agente con nombre del YAML; null si usa la política del repositorio. */
  agent: string | null
  /** Fases que tienen que terminar antes, en el orden del YAML. */
  dependsOn: StageDependency[]
  /** Tipo de la fase en el YAML (`agent`, `command`, `parallel`, `conditional`…). */
  type: string | null
  /** Comando de shell de una fase `command`. */
  command: string | null
  /** Condición que decide si se ejecuta; null: siempre. */
  condition: string | null
  /** Lo que Skynet comprueba en su worktree antes de darla por buena. */
  checks: VerificationCheck[]
  /** Datos de otras fases que lee su prompt, p. ej. `spec.output.summary`. */
  reads: string[]
  status: StageStatus
  attempt: number
  startedAt: string | null
  finishedAt: string | null
  agents: AgentRun[]
  /** La última verificación de fase de cada agente; con una en curso, la fase está «Verificando». */
  verifications: VerificationResult[]
  /** La última petición de una fase `human-approval`; null si no ha preguntado todavía. */
  approval: ApprovalSummary | null
  /** Cuándo se reintenta si falla; null si no se reintenta. */
  retry: RetryPolicy | null
  /** Un reintento programado no arranca antes de esta hora. */
  notBefore: string | null
  /** El `timeout` de la fase en segundos; null si no tiene límite. */
  timeoutSeconds: number | null
  /** El intento tiene que terminar antes de esta hora. */
  deadline: string | null
  /** Cuándo llegó a su límite; null si no llegó. */
  timedOutAt: string | null
  /** Se ha pedido parar alguno de sus agentes y el runner aún no lo ha confirmado. */
  cancelling: boolean
  /** Quién pidió este intento con «Reintentar fase»; null si lo programó el motor. */
  retriedBy: string | null
  /** Hijos de una fase `parallel`, en el orden del YAML; vacío en las demás. */
  children: string[] /** Macrofase de la fase en el YAML (carril del grafo); null si no la dice. */
  phase: Phase | null
  /** La cuota que espera antes de empezar (CQ-A); sigue en `notBefore`. Null si no espera. */
  quotaWait: QuotaWait | null
  /** El juez de una fase `verification` con `judge` (VA-B); null en las demás. */
  judge: StageJudge | null
  /** El nivel de riesgo de lo que cambió la fase (AU-A); null si no se ha medido. */
  risk: Risk | null
}

/** Un fichero que cuenta para el nivel de riesgo y por qué. */
export interface RiskReason {
  path: string
  tier: RiskTier
  kind: 'docs' | 'tests' | 'code' | 'level-2'
  /** Por qué es nivel 2: «ruta», «esquema de BD», «secretos»… */
  label?: string
  /** El trozo de la línea añadida que casó con un patrón. */
  match?: string
}

export type RiskTier = 0 | 1 | 2

/**
 * El nivel de riesgo de un cambio (AU-A), calculado del diff: 0 documentación, 1 código, 2
 * migraciones, CI, dependencias, despliegue, seguridad o secretos.
 */
export interface Risk {
  tier: RiskTier
  /** Una línea: «Nivel 2: `V49__risk.sql` (ruta)». */
  summary: string
  files: number
  /** Líneas añadidas y quitadas sin tests ni documentación. */
  lines: number
  reasons: RiskReason[]
}

/** La cuota que espera una fase: de qué proveedor, qué ventana y cuándo se renueva. */
export interface QuotaWait {
  provider: string
  window: QuotaWindowKind | null
  resetsAt: string | null
}

/** A quién sustituye un agente que es la reserva de otro proveedor sin cuota (CQ-B). */
export interface AgentFallback {
  provider: string
  window: QuotaWindowKind | null
  resetsAt: string | null
}

/**
 * «Continuar tras la cuota» (CQ-A). `maxWaitHours: 0` es sin límite. Con proveedor de reserva
 * (CQ-B), se cambia a él si la espera pasa de `fallbackAfterMinutes`.
 */
export interface QuotaSettings {
  enabled: boolean
  maxWaitHours: number
  maxWaits: number
  fallbackAfterMinutes: number
  updatedAt: string | null
}

/** Una fase que espera ahora a la cuota. */
export interface QuotaWaitRow {
  workflowRunId: string
  workItemKey: string
  stageKey: string
  attempt: number
  provider: string
  window: QuotaWindowKind | null
  resetsAt: string | null
  notBefore: string
}

export type ApprovalRisk = 'LOW' | 'MEDIUM' | 'HIGH'
export type ApprovalStatus =
  'PENDING' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED' | 'CANCELLED' | 'EXPIRED'
export type ApprovalAction = 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES' | 'COMMENT'

/** De dónde viene una puerta de aprobación. */
export interface ApprovalOrigin {
  project: { id: string; key: string; name: string }
  workItem: { id: string; key: string; title: string }
  repository: { id: string; name: string }
  run: { id: string }
  workflow: { key: string; version: number; name: string | null }
  stage: { key: string; name: string | null }
}

/** Una petición de aprobación, sin su evidencia. */
export interface ApprovalSummary {
  id: string
  workflowRunId: string
  stageRunId: string
  stageKey: string
  /** 1 la primera vez; una más cada vez que la puerta vuelve a preguntar. */
  round: number
  /** La acción que se pide aprobar, con sus variables resueltas. */
  action: string
  risk: ApprovalRisk
  status: ApprovalStatus
  createdAt: string
  decidedAt: string | null
  /** Con `CHANGES_REQUESTED`, la invocación que corrige en la fase `revise`. */
  correctionAgentRunId: string | null
  origin: ApprovalOrigin
}

/** Lo que dejó un agente de una fase de la que depende la puerta. */
export interface ApprovalEvidenceAgent {
  agentRunId: string
  childKey: string | null
  status: AgentStatus
  provider: string | null
  costUsd: number | null
  structuredOutput: unknown
  checks: CheckResult[]
  /** Resumen de `GIT_CHANGES`: rama, commits, ficheros y líneas; null si no lo hay. */
  changes: {
    branch: string
    baseCommit: string
    headCommit: string
    commits: number
    files: number
    insertions: number
    deletions: number
    uncommittedFiles: number
  } | null
  artifacts: { id: string; type: string; name: string }[]
}

/** La PR que abre una fase `github-pr` (EN-A). */
export interface PullRequestView {
  repositoryId: string
  fullName: string
  branch: string
  base: string | null
  status: 'PUSHING' | 'PUSHED' | 'OPEN' | 'FAILED' | 'MERGED' | 'CLOSED'
  headSha: string | null
  number: number | null
  url: string | null
  title: string | null
  error: string | null
  updatedAt: string
}

/** Un escape del juez (EV-A): lo que aprobó, se fusionó y luego se revirtió o se marcó a mano. */
export interface EscapeView {
  id: string
  runId: string
  workItemKey: string
  workItemTitle: string
  repositoryId: string
  repositoryName: string
  pullRequestNumber: number | null
  pullRequestUrl: string | null
  mergedSha: string | null
  origin: 'revert-pr' | 'revert-push' | 'manual'
  /** La PR del revert (`#13`) o el commit. */
  seenRef: string | null
  seenUrl: string | null
  note: string | null
  createdBy: string
  createdAt: string
}

/** Un check de la CI de un commit, como lo resume Skynet. */
export interface CiCheck {
  name: string
  state: 'success' | 'failure' | 'pending' | 'skipped' | string
  url?: string | null
  [key: string]: unknown
}

/** La espera de la CI de una fase `github-check` (EN-B). */
export interface CiCheckView {
  stageRunId: string
  stageKey: string
  /** `JUDGING`: la CI pasó y los jueces miran lo que cambió después del juicio (AU-B). */
  status: 'WAITING' | 'FIXING' | 'PUSHING' | 'JUDGING' | 'PASSED' | 'FAILED'
  headSha: string
  round: number
  checks: CiCheck[]
  error: string | null
  waitingSince: string
  updatedAt: string
}

/** La fusión de una fase `github-merge` (EN-C). */
export interface MergeView {
  stageRunId: string
  method: 'squash' | 'merge' | 'rebase'
  status: 'PENDING' | 'MERGED' | 'FAILED'
  expectedSha: string
  mergedSha: string | null
  branchDeleted: boolean
  error: string | null
}

/** La PR que fusionará la fase que desbloquea una aprobación, con la CI del commit que se aprueba. */
export interface ApprovalPullRequest {
  number: number | null
  url: string | null
  branch: string
  base: string | null
  headSha: string | null
  ci: { status: CiCheckView['status']; round: number; checks: CiCheck[] } | null
  /** Qué se juzgó y qué se fusiona (AU-B); null si la ejecución no tiene un juez que aprobara. */
  judged: JudgedMerge | null
}

/** Lo juzgado frente a lo que se fusiona (AU-B). */
export interface JudgedMerge {
  /** La fase con juez que aprobó. */
  stageKey: string
  /** El commit que vieron sus jueces (una instantánea si había cambios sin confirmar). */
  judgedSha: string
  /** El commit de la PR. */
  mergeSha: string | null
  /** Qué pasó con lo de en medio; null si cambió y no se juzgó. */
  status: 'UNCHANGED' | 'SKIPPED' | 'JUDGING' | 'REFUTING' | 'PASSED' | 'FAILED' | null
  /** El nivel de riesgo del delta, si se midió. */
  tier?: number | null
  /** La nota de los jueces del delta, si lo juzgaron. */
  score?: number | null
  /** «Juzgado en `abc1234` · se fusiona `def5678` · delta revisado (nivel 1, 3,9 / 5)». */
  text: string
}

/** La foto que se tomó al crear la petición. */
export interface ApprovalEvidence {
  origin: ApprovalOrigin
  /** La fase cuyo agente corrige al pedir cambios; null si no hay a quién. */
  revise: { stageKey: string; agentRunId: string | null } | null
  dependencies: {
    stageKey: string
    name: string | null
    type: string
    status: StageStatus
    agents: ApprovalEvidenceAgent[]
  }[]
  costUsd: number
  /** Lo que se permitirá a las fases que desbloquea la aprobación. */
  grants: StagePolicy[]
  consequences: { approve: string; reject: string; requestChanges: string | null }
  /** Desde la segunda ronda, los cambios que se pidieron en la anterior; null en la primera. */
  changesRequested: {
    approvalId: string
    round: number
    comment: string
    actor: string
    createdAt: string
    correctionAgentRunId: string
  } | null
  /** Si la aprobación desbloquea una fase `github-merge`, la PR que fusionará; si no, null. */
  pullRequest: ApprovalPullRequest | null
  /** Si la puerta tiene `autoApprove` (AU-C): si se aprobó sola y cada condición. */
  autoApprove: AutoApproveCheck | null
  /** Lo que los agentes de la ejecución supusieron sin comprobarlo (ES-C); no para nada. */
  assumptions?: Assumption[]
}

/** Un supuesto de un agente (ES-C), con la fase que lo dejó. */
export interface Assumption {
  stageKey: string
  text: string
}

/** Lo que miró Skynet para aprobar sola una puerta de fusión (AU-C). */
export interface AutoApproveCheck {
  approved: boolean
  /** «Se aprueba sola: …» o «No se aprueba sola: nivel 2 por …». */
  note: string
  conditions: { id: string; met: boolean; text: string }[]
}

/** Una decisión o un comentario, que no se reescribe. */
export interface ApprovalDecision {
  id: string
  action: ApprovalAction | 'CANCEL'
  comment: string | null
  /** El usuario de la web, o `skynet` si se canceló con su ejecución o se aprobó sola (AU-C). */
  actor: string
  createdAt: string
}

export interface ApprovalView extends Omit<ApprovalSummary, 'origin'> {
  evidence: ApprovalEvidence
  decisions: ApprovalDecision[]
}

export interface RunTotals {
  inputTokens: number | null
  outputTokens: number | null
  cacheReadTokens: number | null
  cacheCreationTokens: number | null
  costUsd: number | null
  /** Suma de la cuota atribuida a sus agentes, por CLI y ventana. */
  quota: QuotaShare[]
}

export type QuotaWindowKind = 'five_hour' | 'seven_day'

/** Parte de una ventana de cuota atribuida a un agente o a una ejecución (de 0 a 1). */
export interface QuotaShare {
  provider: string
  bucket: string
  window: QuotaWindowKind
  share: number
  /** `measured`: medido con el agente solo; `prorated`: repartido con otros o con lo de fuera. */
  method: 'measured' | 'prorated'
}

/** Una ventana que pasa del 90 % (`limit`) o, la semanal, va por encima de su ritmo (`pace`). */
export interface QuotaWarning {
  runnerId: string
  runnerName: string
  provider: string
  providerName: string
  bucket: string
  label: string | null
  window: QuotaWindowKind
  usedFraction: number
  pace: number | null
  resetsAt: string | null
  reason: 'limit' | 'pace'
}

/** La última muestra de una ventana de la cuota de un CLI (GET /api/usage/windows). */
export interface UsageWindow {
  provider: string
  providerName: string
  bucket: string
  label: string | null
  window: QuotaWindowKind
  usedFraction: number
  resetsAt: string | null
  plan: string | null
  source: 'invocation' | 'poll'
  observedAt: string
  stale: boolean
  /** Dónde estaría gastando igual toda la ventana, de 0 a 1. */
  pace: number | null
  /** De lo gastado en la ventana, lo atribuido a agentes de Skynet. */
  skynet: number
}

export interface UsageWindows {
  runners: { id: string; name: string; windows: UsageWindow[] }[]
}

/** La evolución de la ventana actual de un CLI (GET /api/usage/history). */
export interface UsageHistory {
  runnerId: string
  provider: string
  bucket: string
  window: QuotaWindowKind
  windowStart: string
  resetsAt: string | null
  points: UsagePoint[]
}

export interface UsagePoint {
  at: string
  usedFraction: number
  skynet: number
  source: 'invocation' | 'poll'
}

/** Lo que cuesta un agente en la cuota de su CLI (GET /api/usage/agents). */
export interface AgentUsage {
  workflowKey: string
  workflowName: string | null
  agent: string
  provider: string
  providerName: string
  runs: number
  succeeded: number
  fiveHour: number
  sevenDay: number
  sevenDayPerRun: number
  sevenDayPerSuccess: number | null
  inputTokens: number | null
  outputTokens: number | null
}

/** Una de las ejecuciones que más cuota han gastado (GET /api/usage/runs). */
export interface RunUsage {
  id: string
  workItemKey: string | null
  workItemTitle: string | null
  workflowKey: string
  workflowName: string | null
  status: RunStatus
  createdAt: string
  quota: QuotaShare[]
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
  /** Cuándo se archivó la ejecución; no cuenta el archivado de su trabajo o proyecto. */
  archivedAt: string | null
  /** Se pidió cancelarla y aún no ha terminado: «Cancelando». */
  cancelRequestedAt: string | null
  /** Fase y agente que aún no han terminado; null cuando todo ha terminado. */
  currentStageRunId: string | null
  currentAgentRunId: string | null
  totals: RunTotals
  /** Workflow y versión que sigue; null si la versión ya no se puede leer. */
  workflow: WorkflowRef | null
  stages: StageRun[]
  /** El nivel de riesgo de la ejecución, el máximo de sus fases (AU-A); null si no se ha medido. */
  risk: Risk | null
}

export interface WorkflowRef {
  key: string
  version: number
  name: string | null
}

/** Lo que se le permitirá al agente de una fase si se lanza el workflow en un repositorio. */
export interface StagePolicy {
  stage: string
  name: string | null
  /** En una fase `command` solo cuentan el entorno y el comando. */
  type: string
  agent: string | null
  allowedTools: string[]
  permissionMode: PermissionMode | null
  /** null: todas las que permite el runner. */
  environment: string[] | null
  /** Límites que fija el agente; null: los del lanzamiento. */
  maxTurns: number | null
  maxBudgetUsd: number | null
  timeoutMinutes: number | null
  /** Modelo que pide el agente; null: el global. */
  model: string | null
  /** Comando de una fase `command`. */
  command: string | null
  /** Proveedor que pide el agente; null en un comando o sin agente con nombre (lo elige el lanzamiento). */
  provider: string | null
  /** Lo de la política que el proveedor ignora. */
  unsupported: ('allowedTools' | 'maxTurns')[]
}

export interface RunPage {
  items: Run[]
  page: number
  size: number
  total: number
}

/** Un proveedor de agentes que tiene un runner (Claude Code, Codex, Antigravity…). */
export interface ProviderInfo {
  id: string
  /** Versión de su CLI; null si no respondió a --version. */
  version: string | null
  /** El CLI es un simulador de pruebas (fake-claude, fake-codex…), no el real (AR-A). */
  simulated: boolean
}

export interface Runner {
  id: string
  name: string
  capacity: number
  activeAgents: number
  runnerVersion: string | null
  /** Versión de Claude Code; obsoleto, usa providers. */
  providerVersion: string | null
  registeredAt: string
  lastHeartbeatAt: string | null
  status: 'ONLINE' | 'STALE'
  /** Cuándo se olvidó; null si no. */
  archivedAt: string | null
  /** Proveedores de agentes que tiene, con la versión de su CLI. */
  providers: ProviderInfo[]
}

/** Lo que un proveedor admite además de arrancar una sesión. */
export type ProviderCapability =
  'RESUME' | 'FORK' | 'STRUCTURED_OUTPUT' | 'MAX_TURNS' | 'MEMORY_TOOLS'

/** Un proveedor de agentes que Skynet sabe ejecutar. */
export interface ProviderKind {
  id: string
  displayName: string
  capabilities: ProviderCapability[]
  /** Runners en línea que lo tienen. */
  onlineRunners: number
}

/** Agentes en cola de un proveedor que no tiene ningún runner en línea. */
export interface ProviderShortage {
  provider: string
  displayName: string
  queuedAgents: number
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
  providerShortages: ProviderShortage[]
  quotaWarnings: QuotaWarning[]
  generatedAt: string
}

export type MetricsPeriod = '24h' | '7d' | '30d'

/** Métricas de las ejecuciones creadas en un periodo (GET /api/dashboard/metrics). */
export interface RunMetrics {
  since: string
  until: string
  /** Tamaño de cada tramo de `buckets`. */
  bucket: 'hour' | 'day'
  total: number
  active: number
  succeeded: number
  failed: number
  cancelled: number
  medianDurationSeconds: number | null
  inputTokens: number | null
  outputTokens: number | null
  costUsd: number | null
  buckets: MetricsBucket[]
}

export interface MetricsBucket {
  start: string
  total: number
  succeeded: number
  failed: number
  cancelled: number
  costUsd: number | null
}

export interface WorkflowDefinition {
  id: string
  key: string
  version: number
  sourceYaml: string
  createdAt: string
}

/** Estado de una versión de un workflow. */
export type DefinitionStatus = 'DRAFT' | 'VALIDATED' | 'PUBLISHED'

/** Una versión de un workflow, sin su YAML. */
export interface VersionView {
  id: string
  version: number
  status: DefinitionStatus
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

/** Un workflow en la lista: su última versión publicada y su borrador, si lo tiene. */
export interface WorkflowSummary {
  id: string
  key: string
  name: string | null
  description: string | null
  published: VersionView | null
  draft: VersionView | null
  createdAt: string
  archivedAt: string | null
  /** Lo que la tarjeta cuenta de la definición mostrada; null si no se pudo leer. */
  overview: WorkflowOverview | null
}

/** Resumen de una definición: fases, puertas, proveedores y qué entrega (WF-B). */
export interface WorkflowOverview {
  stages: number
  /** Agentes que corren a la vez dentro de fases parallel, sumados. */
  parallelChildren: number
  /** Fases human-approval: las que te piden aprobar. */
  gates: number
  /** Agentes distintos que juzgan en fases verification. */
  judges: number
  /** Sin repetir y por orden de aparición; `repository` si una fase usa el agente del repositorio. */
  providers: string[]
  fallbackProviders: string[]
  /** Macrofases con alguna fase, en el orden de los carriles del grafo. */
  lanes: string[]
  inputs: { name: string; required: boolean }[]
  /** Tiene una fase github-pr. */
  delivers: boolean
  /** Ningún agente puede cambiar el repositorio. */
  readOnly: boolean
}

export interface WorkflowView {
  workflow: WorkflowSummary
  /** De la más reciente a la más antigua. */
  versions: VersionView[]
}

/** Algo que corregir en una definición; `line` y `column` empiezan en 1. */
export interface Problem {
  severity: 'ERROR' | 'UNSUPPORTED' | 'WARNING'
  path: string
  line: number | null
  column: number | null
  message: string
}

export interface Validation {
  /** Sin errores. */
  valid: boolean
  /** Válida y sin nada que el motor aún no ejecute. */
  publishable: boolean
  problems: Problem[]
}

export interface InputDefinition {
  name: string
  type: 'string' | 'text' | 'number' | 'boolean'
  required: boolean
  defaultValue: string | number | boolean | null
  description: string | null
}

export interface AgentDefinition {
  name: string
  description: string | null
  prompt: string | null
  tools: string[] | null
  permissionMode: PermissionMode | null
  maxTurns: number | null
  maxBudgetUsd: number | null
  timeoutMinutes: number | null
  /** null: el modelo global de Skynet. */
  model: string | null
  /** Por ahora solo `claude-code`. */
  provider: string | null
  /** JSON Schema de la salida estructurada, o null si no devuelve ninguna. */
  output: { type: 'object'; properties: Record<string, { type: string }> } | null
  /**
   * Qué hace si su cuenta se queda sin cuota (CQ-A): `wait`, `fail` o `fallback` (CQ-B). Con null,
   * `fallback` si tiene reserva y `wait` si no.
   */
  onQuota: 'wait' | 'fail' | 'fallback' | null
  /** Proveedor de reserva (CQ-B), o null. */
  fallback: { provider: string; model: string | null } | null
}

/** Las macrofases de `phase`, en el orden de los carriles del grafo. */
export const PHASES = ['spec', 'plan', 'implement', 'test', 'review', 'deliver'] as const
export type Phase = (typeof PHASES)[number]

export interface StageDefinition {
  id: string
  name: string | null
  type: string
  agent: string | null
  prompt: string | null
  dependsOn: { stage: string; optional: boolean }[]
  workspace: 'INHERIT' | 'ISOLATED'
  workspaceFrom: string | null
  /** Comando de shell de una fase `command`. */
  command: string | null
  /** Agentes de una fase `parallel`, que se ejecutan a la vez. */
  children: { id: string; agent: string; prompt: string | null; retry: RetryPolicy | null }[]
  /** Condición que decide si la fase se ejecuta; null: siempre. */
  condition: string | null
  /** Salida estructurada de la fase en línea, que manda sobre el `output` de su agente. */
  outputSchema: { type: 'object'; properties: Record<string, { type: string }> } | null
  /** La misma, en un fichero del repositorio que el runner lee del worktree. */
  outputSchemaPath: string | null
  /** Lo que Skynet comprueba en su worktree antes de darla por buena (`artifacts`, `checks`, `scope`). */
  checks: VerificationCheck[]
  /** Lo que pide una fase `human-approval`; null en las demás. */
  approval: { action: string; risk: ApprovalRisk; revise: string | null } | null
  /** Cuándo se reintenta si falla; null si no se reintenta. */
  retry: RetryPolicy | null
  /** Tiempo máximo de cada intento en segundos; null si no tiene límite. */
  timeoutSeconds: number | null
  /** Macrofase de la fase, que el grafo dibuja como carril; null si no la dice. */
  phase: Phase | null
  /** Acceso de su agente a la memoria del proyecto; null para `write`, lo predeterminado. */
  memory: 'off' | 'read' | 'write' | null
  /** La PR que abre una fase `github-pr`; null en las demás. */
  pullRequest: {
    title: string | null
    body: string | null
    base: string | null
    draft: boolean
    labels: string[]
  } | null
  /** Cómo espera la CI una fase `github-check`; null en las demás. */
  ci: {
    required: string[]
    ifNoChecks: 'pass' | 'fail'
    fix: string | null
    maxRounds: number
  } | null
  /** Cómo fusiona la PR una fase `github-merge`; null en las demás. */
  merge: { method: 'squash' | 'merge' | 'rebase'; deleteBranch: boolean } | null
  /** El juez de una fase `verification` (VA-B); null si no tiene. */
  judge: JudgeSpec | null
}

/** Una lente de juez: qué mira además de la rúbrica. */
export type JudgeLens = 'correctness' | 'security' | 'performance' | 'reproduce' | 'completeness'

/** El bloque `judge` de una fase `verification`, con `fix` y `maxRounds` de la fase. */
export interface JudgeSpec {
  agents: { agent: string; lens: JudgeLens | null }[]
  /** Los criterios del YAML; vacío con `rubricFile` o con la rúbrica de siempre. */
  rubric: { id: string; text: string; weight: number; required: boolean }[]
  rubricFile: string | null
  threshold: number
  fix: string | null
  maxRounds: number
  /** Escépticos por hallazgo que bloquea (VA-C); 0 sin verificación adversarial. */
  refute: number
  /** El agente de los escépticos; null para el del primer juez. */
  refuteAgent: string | null
  /** La guía de pruebas como fase (VA-D); null en un juez con rúbrica. */
  guide: GuideSpec | null
}

/** El bloque `guide` de una fase `verification` (VA-D). */
export interface GuideSpec {
  /** Los globs de la guía, p. ej. `docs/tests/probar-*.md`. */
  paths: string[]
  agent: string
  /** Las secciones que no se ejecutan, por cómo empieza su título. */
  skip: string[]
  /** Si sin guía en el cambio la fase pasa en vez de fallar. */
  skipIfMissing: boolean
}

/** Un paso de la guía de pruebas, como lo cuenta quien la ejecuta (VA-D). */
export interface GuideStep {
  guide: string | null
  section: string | null
  step: string | null
  status: 'passed' | 'failed' | 'blocked' | 'skipped'
  evidence: string | null
}

/** La clase de un hallazgo (AP-A): una lista cerrada, para contar lo que se repite. */
export type FindingCategory =
  | 'tests'
  | 'caso-limite'
  | 'errores'
  | 'entradas'
  | 'secretos'
  | 'concurrencia'
  | 'rendimiento'
  | 'api'
  | 'datos'
  | 'alcance'
  | 'estilo'
  | 'documentacion'
  | 'otro'

/** Un hallazgo de un juez. */
export interface JudgeFinding {
  file: string | null
  line: number | null
  severity: 'blocking' | 'minor'
  /** Su clase (AP-A); ausente en los veredictos de antes. */
  category?: FindingCategory
  text: string
  judge: string
  /** Si lo refutaron los escépticos (VA-C); null o ausente si no se intentó. */
  refuted?: boolean | null
  /** Por qué lo refutaron (o no). */
  refutation?: string | null
  /**
   * Si se descartó por ya visto (RH-B), el desenlace con el que coincidió: refutado en una ronda
   * anterior, o intencionado o que no aplica según una persona.
   */
  known?: 'refutado' | 'aceptado' | 'irrelevante' | null
}

/**
 * El desenlace de un hallazgo (RH-A): lo pone el motor (abierto, refutado, corregido, aparcado) o
 * una persona (aceptado: es real pero intencionado; irrelevante: el juez se equivocó).
 */
export type FindingOutcome =
  'abierto' | 'refutado' | 'corregido' | 'aparcado' | 'aceptado' | 'irrelevante'

/** Un hallazgo con ruido (refutado o que no aplicaba) de un juez, en Fábrica › Jueces (RH-C). */
export interface JudgeSample {
  id: string
  runId: string
  file: string | null
  line: number | null
  text: string
  outcome: FindingOutcome
  outcomeBy: string | null
  outcomeReason: string | null
  createdAt: string
}

/** Ruido y acierto de un juez con su lente, en un proveedor y un modelo (RH-C). */
export interface JudgeStatsRow {
  provider: string | null
  model: string | null
  judge: string
  lens: string | null
  findings: number
  refuted: number
  irrelevant: number
  fixed: number
  accepted: number
  parked: number
  open: number
  /** (refutados + no aplica) / hallazgos, de 0 a 1. */
  noise: number
  /** corregidos / hallazgos, de 0 a 1. */
  hits: number
  /** Menos de 10 hallazgos: la cifra dice poco. */
  fewData: boolean
  noisy: JudgeSample[]
  /** EV-B: cambios que aprobó y ya tienen desenlace. */
  judged: number
  /** Su PR se fusionó y no se revirtió. */
  kept: number
  /** Se revirtió o alguien dijo que se le escapó un fallo (EV-A). */
  escaped: number
  /** Alguien lo rechazó o pidió cambios en una puerta después del juez. */
  rejected: number
  /** La PR se cerró sin fusionar. */
  closed: number
  /** Menos de 10 desenlaces. */
  fewOutcomes: boolean
  /** Los desenlaces por tramo de nota: por debajo de 4, de 4 a 4,5 y de 4,5 en adelante. */
  calibration: JudgeBand[]
  /** Los últimos cambios que aprobó y se le escaparon. */
  escapes: JudgeEscapeSample[]
}

export interface JudgeBand {
  label: string
  from: number
  to: number | null
  judged: number
  kept: number
  escaped: number
  rejected: number
  closed: number
  /** kept / judged, de 0 a 1. */
  keptRatio: number
}

export interface JudgeEscapeSample {
  runId: string
  pullRequestNumber: number | null
  origin: EscapeView['origin']
  seenRef: string | null
  note: string | null
  score: number | null
  createdAt: string
}

/** Lo que debería decir un buen juez de un caso del banco (EV-C). */
export type EvalExpected = 'debe-suspender' | 'debe-aprobar'

/** Una repetición de un caso con un juez (EV-C). */
export interface EvalRunView {
  id: string
  caseId: string
  /** Las dos de un A/B lo comparten. */
  pairId: string | null
  judge: string
  lens: string | null
  provider: string | null
  model: string | null
  /** La ejecución de evaluación que la lleva. */
  workflowRunId: string | null
  agentRunId: string | null
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'
  score: number | null
  passed: boolean | null
  /** Si dijo lo esperado. */
  correct: boolean | null
  findings: { file?: string | null; line?: number | null; severity: string; text: string }[]
  summary: string | null
  error: string | null
  costUsd: number | null
  createdAt: string
}

/** Un caso del banco de evaluaciones (EV-C): un cambio juzgado para volver a juzgarlo. */
export interface EvalCase {
  id: string
  runId: string
  repositoryId: string
  repositoryName: string
  workItemKey: string
  title: string
  stageKey: string
  baseSha: string
  headSha: string
  expected: EvalExpected
  fromEscape: boolean
  note: string | null
  createdBy: string
  createdAt: string
  runs: EvalRunView[]
}

/** Cuánto acierta un juez con un modelo en el banco. */
export interface EvalAccuracy {
  judge: string
  provider: string | null
  model: string | null
  runs: number
  correct: number
  failed: number
  ratio: number
}

export interface EvalBank {
  cases: EvalCase[]
  accuracy: EvalAccuracy[]
}

/** Un agente del workflow del caso que puede juzgarlo. */
export interface EvalJudgeOption {
  agent: string
  lens: string | null
  provider: string | null
  model: string | null
  /** Es uno de los jueces de la fase. */
  judge: boolean
}

export interface EvalJudgeChoice {
  agent: string
  lens?: string | null
  provider?: string | null
  model?: string | null
}

/** Un hallazgo del registro (RH-A), con su desenlace. */
export interface FindingView {
  id: string
  runId: string
  stageRunId: string
  stageKey: string
  round: number
  /** judge: el juez de la fase; delta: los jueces de lo corregido para la CI (AU-B). */
  source: 'judge' | 'delta'
  judge: string
  lens: string | null
  provider: string | null
  model: string | null
  file: string | null
  line: number | null
  severity: 'blocking' | 'minor'
  /** Su clase, de la lista que dice el juez (AP-A); `otro` si no dijo ninguna. */
  category: FindingCategory
  text: string
  outcome: FindingOutcome
  /** `skynet` o quien lo decidió; null mientras está abierto. */
  outcomeBy: string | null
  outcomeReason: string | null
  outcomeAt: string | null
  /** Las veces que volvió a salir en rondas siguientes de la misma fase. */
  repeats: number
  /** La primera vez que salió en el repositorio, en otra fase. */
  firstSeenId: string | null
  firstSeenRunId: string | null
  createdAt: string
}

/** Una lección (AP-B): lo que se repite en los hallazgos de un repositorio, como regla propuesta. */
export type LessonKind = 'prevenir' | 'no-senalar'
export type LessonStatus = 'propuesta' | 'aplicada' | 'descartada'

export interface Lesson {
  id: string
  projectId: string
  projectName: string
  repositoryId: string
  repositoryName: string
  category: FindingCategory
  kind: LessonKind
  status: LessonStatus
  /** El texto de la plantilla, con ejemplos. */
  suggestedText: string
  /** El que se aplicó (editado o no); se queda al desactivarla. */
  appliedText: string | null
  /** Ejecuciones distintas con casos. */
  runs: number
  findings: number
  /** La restricción de la memoria, si es `prevenir` y se aplicó. */
  observationId: string | null
  decidedBy: string | null
  decidedAt: string | null
  reason: string | null
  createdAt: string
  updatedAt: string
}

/** Un hallazgo que justifica una lección, con su ejecución. */
export interface LessonFinding {
  id: string
  runId: string
  workItemId: string
  stageKey: string
  round: number
  judge: string
  file: string | null
  line: number | null
  severity: 'blocking' | 'minor'
  text: string
  outcome: FindingOutcome
  outcomeBy: string | null
  outcomeReason: string | null
  outcomeAt: string
}

export interface LessonDetail {
  lesson: Lesson
  findings: LessonFinding[]
}

/** El juez de una fase en una ejecución: en qué ronda va y el veredicto de la última ronda. */
export interface StageJudge {
  /** SKIPPED: el cambio no llegó a `minRisk` y la fase pasó sin juez (AU-C). */
  status: 'CHECKING' | 'JUDGING' | 'REFUTING' | 'FIXING' | 'PASSED' | 'FAILED' | 'SKIPPED'
  round: number
  /** La ronda de `judgements`: la anterior mientras se corrige. */
  shownRound: number
  /** Rondas de corrección como mucho; 0 sin `fix`. */
  maxRounds: number
  fix: string | null
  threshold: number
  /** La media de las medias; null sin veredicto. */
  score: number | null
  passed: boolean | null
  approvals: number | null
  judges: number | null
  findings: JudgeFinding[]
  judgements: {
    judge: string
    lens: JudgeLens | null
    agentRunId: string
    status: 'PENDING' | 'DONE' | 'FAILED'
    score: number | null
    passed: boolean | null
    criteria: {
      id: string
      score: number
      reason: string | null
      weight: number
      required: boolean
    }[]
    findings: JudgeFinding[]
    summary: string | null
    error: string | null
  }[]
  error: string | null
  /** Escépticos por hallazgo que bloquea; 0 sin verificación adversarial. */
  refute: number
  /** Cuántos hallazgos se están intentando refutar ahora. */
  refuting: number
  /** El aviso si no se pudieron refutar todos por los topes. */
  refuteNote: string | null
  /** La guía de pruebas de la fase (VA-D), con los pasos de la última ronda; null en un juez. */
  guide: {
    paths: string[]
    /** Las guías que se ejecutaron; vacío sin guía en el cambio o antes de la primera ronda. */
    guides: string[]
    steps: GuideStep[]
    summary: string | null
    /** «2 de 3 pasos pasan · 1 no se pudo»; null antes de la primera ronda. */
    note: string | null
  } | null
  /** El nivel de riesgo de lo que se juzgó (AU-C); null o ausente sin medir. */
  risk?: number | null
  /** El nivel desde el que se juzga; 0 siempre. */
  minRisk?: number
}

/** Una comprobación de fase: `artifacts`, `tests`, `diff`, `commit`, `scope` o `command`. */
export interface VerificationCheck {
  name:
    | 'artifacts'
    | 'tests'
    | 'tests-intact'
    | 'diff'
    | 'commit'
    | 'scope'
    | 'protected'
    | 'command'
    | 'spec'
  /** El comando de `command`. */
  command: string | null
  /** Los globs de `artifacts` y `scope`. */
  paths: string[]
}

/** El workflow leído del YAML. */
export interface WorkflowModel {
  key: string
  name: string | null
  description: string | null
  inputs: InputDefinition[]
  agents: AgentDefinition[]
  stages: StageDefinition[]
  /** `risk` en la raíz (AU-A): rutas y patrones que suben un cambio a riesgo 2. */
  risk: { paths: string[]; patterns: string[] }
}

/** Una versión con su YAML, su validación y el workflow leído. */
export interface DefinitionDetail {
  id: string
  workflowId: string
  key: string
  version: number
  status: DefinitionStatus
  sourceYaml: string
  /** Hay que enviarla al guardar o publicar: si otro cambió el borrador, se rechaza. */
  revision: number
  createdAt: string
  updatedAt: string
  publishedAt: string | null
  archivedAt: string | null
  validation: Validation
  definition: WorkflowModel | null
}

export interface ValidationView {
  key: string | null
  validation: Validation
  definition: WorkflowModel | null
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
  | 'PROMPT'
  | 'RESULT'
  | 'LOG'
  | 'GIT_CHANGES'
  | 'DIFF'
  | 'VERIFICATION_LOG'
  | 'TEST_REPORT'
  /** Fichero que la fase declara en `artifacts` (#145); su nombre es la ruta relativa. */
  | 'DECLARED_FILE'

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
  /** `STAGE`: la verificación de una fase con comprobaciones, que decide si la fase pasa. */
  trigger: 'AUTO' | 'MANUAL' | 'STAGE'
  status: VerificationStatus
  /** Comando de validación del repositorio, o null si esta verificación no lo ejecuta. */
  command: string | null
  exitCode: number | null
  signal: string | null
  error: string | null
  tests: TestTotals | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  live: boolean
  /** Fase que verifica, con `trigger` `STAGE`. */
  stageRunId: string | null
  /** Las comprobaciones de la fase; `passed` es null mientras no ha terminado. */
  checks: CheckResult[]
}

export interface CheckResult {
  name: string
  /** Lo que comprueba (los globs o el comando). */
  target: string | null
  passed: boolean | null
  /** Por qué no pasó. */
  reason: string | null
  /** En `artifacts`: ficheros declarados que no se guardaron, con el motivo (#145). */
  omitted: { path: string; reason: string }[]
  /** Lo que se avisa sin fallar, p. ej. menos aserciones en `tests-intact` (VA-A). */
  warnings: string[]
}

/** Lanzamiento de un agente; los límites que falten usan los valores por defecto del servidor. */
export type MemoryKind =
  | 'DECISION'
  | 'BUGFIX'
  | 'DISCOVERY'
  | 'PATTERN'
  | 'CONFIG'
  | 'PITFALL'
  | 'CONSTRAINT'
  | 'SESSION_SUMMARY'
  | 'RUN_LOG'

export const MEMORY_KINDS: { value: MemoryKind; label: string }[] = [
  { value: 'DECISION', label: 'Decisión' },
  { value: 'BUGFIX', label: 'Bug resuelto' },
  { value: 'DISCOVERY', label: 'Descubrimiento' },
  { value: 'PATTERN', label: 'Patrón' },
  { value: 'CONFIG', label: 'Configuración' },
  { value: 'PITFALL', label: 'Trampa' },
  { value: 'CONSTRAINT', label: 'Restricción' },
  { value: 'SESSION_SUMMARY', label: 'Resumen de sesión' },
  { value: 'RUN_LOG', label: 'Bitácora' },
]

export type MemoryOrigin = 'AGENT' | 'HUMAN' | 'SYSTEM'

/** Una observación de la memoria del proyecto, en su versión actual. */
export interface MemoryObservation {
  id: string
  projectId: string
  /** `null`: es de todo el proyecto. */
  repositoryId: string | null
  kind: MemoryKind
  title: string
  topicKey: string | null
  what: string
  why: string | null
  where: string | null
  learned: string | null
  tags: string[]
  /** Quién escribió la versión actual. */
  origin: MemoryOrigin
  sourceWorkflowRunId: string | null
  sourceAgentRunId: string | null
  pinned: boolean
  /** Se enmascaró algún secreto al guardarla. */
  redacted: boolean
  version: number
  createdAt: string
  updatedAt: string
  updatedBy: string | null
  archivedAt: string | null
}

export interface MemoryPage {
  items: MemoryObservation[]
  page: number
  size: number
  total: number
}

/** Una versión de una observación, tal como quedó al guardarla. */
export interface MemoryRevision {
  observationId: string
  version: number
  kind: MemoryKind
  title: string
  topicKey: string | null
  what: string
  why: string | null
  where: string | null
  learned: string | null
  tags: string[]
  origin: MemoryOrigin
  sourceAgentRunId: string | null
  changedBy: string | null
  changedAt: string
}

/** Lo que se puede cambiar de una observación al editarla. */
export type MemoryContent = Pick<
  MemoryObservation,
  'kind' | 'title' | 'topicKey' | 'what' | 'why' | 'where' | 'learned' | 'tags'
>

export interface MemoryQuery {
  /** Id de un repositorio, `project` para lo de todo el proyecto, o nada para todo. */
  repository?: string
  kind?: MemoryKind
  origin?: MemoryOrigin
  pinned?: boolean
  archived?: ArchivedFilter
  q?: string
  page?: number
  size?: number
}

export type MemoryVia = 'PROMPT' | 'CONTEXT' | 'SEARCH' | 'GET' | 'SAVE'

/** Lo que una invocación de agente hizo con la memoria. */
export interface AgentMemory {
  agentRunId: string
  projectId: string
  /** `null`: la invocación no usó la memoria. */
  mode: 'READ' | 'WRITE' | null
  accesses: {
    observationId: string
    version: number
    via: MemoryVia
    kind: MemoryKind
    /** El título de esa versión. */
    title: string
    repositoryId: string | null
    archived: boolean
    times: number
    firstAt: string
  }[]
}

/** Un repositorio git que un runner encontró en sus carpetas raíz (IP-A). */
export interface DiscoveredRepository {
  path: string
  /** Nombre de la carpeta. */
  name: string
  projectName: string
  /** `package.json`, `settings.gradle.kts`, `pom.xml`, `pyproject.toml`, `Cargo.toml`, `go.mod` o `directory`. */
  projectNameSource: string
  description: string | null
  /** El manifiesto o el README del que sale la descripción. */
  descriptionSource: string | null
  /** Sin credenciales. */
  remoteUrl: string | null
  defaultBranch: string
  defaultBranchSource: 'origin/HEAD' | 'current' | 'default'
  tool: string | null
  validationCommand: string | null
  testReportPaths: string[]
  protectedPaths: string[]
  lastCommit: string | null
  lastCommitSubject: string | null
  lastCommitAt: string | null
  dirty: boolean
  agentInstructions: boolean
}

/** Lo que GitHub dice del remoto de una entrada del catálogo (IP-B). */
export interface CatalogGitHub {
  fullName: string
  covered: boolean
  description: string | null
  htmlUrl: string
}

export interface CatalogEntry {
  repository: DiscoveredRepository
  /** El proyecto en el que ya está registrada esa ruta. */
  registered: {
    projectId: string
    projectKey: string
    projectName: string
    repositoryId: string
  } | null
  github: CatalogGitHub | null
}

/** El catálogo de repositorios de un runner. */
export interface RunnerCatalog {
  runnerId: string
  runnerName: string
  lastHeartbeatAt: string | null
  scannedAt: string
  receivedAt: string
  roots: string[]
  truncated: boolean
  repositories: CatalogEntry[]
}

export interface ImportProjectRequest {
  key: string
  name: string
  description?: string
  repository: {
    name: string
    localPath: string
    remoteUrl?: string
    defaultBranch?: string
    validationCommand?: string
    testReportPaths?: string[]
    protectedPaths?: string[]
  }
}

export interface LaunchRequest {
  repositoryId: string
  /** Versión publicada que se lanza; sin ella, la última de `adhoc`. */
  definitionId?: string
  /** Datos de entrada del workflow, por nombre. */
  inputs?: Record<string, string | number | boolean>
  /** Atajo para el dato `prompt` de los workflows que lo piden. */
  prompt?: string
  maxTurns?: number
  maxBudgetUsd?: number
  timeoutMinutes?: number
  /** Proveedor de las fases sin agente con nombre (como la de `adhoc`); sin él, Claude Code. */
  provider?: string
  /** Modelo de esas fases; sin él, el predeterminado del proveedor. */
  model?: string
}

/** Paleta guardada en el servidor: un color base `#rrggbb` por pieza, o `null` para el de Skynet. */
export interface Appearance {
  colors: Partial<Record<'primary' | 'ok' | 'warn' | 'bad' | 'live' | 'idle', string | null>>
  /** Paquete de iconos (IC-A); los servidores anteriores no lo mandan. */
  icons?: IconPack
  updatedAt: string | null
}

/** La conexión con la GitHub App (`GET /api/github`). */
export interface GitHubStatus {
  /** Si están las tres variables de la App. */
  configured: boolean
  /** Las variables que faltan. */
  missing: string[]
  apiUrl: string
  appId: string | null
  /** La App según GitHub; null si no se pudo comprobar. */
  app: GitHubApp | null
  /** Por qué no se pudo comprobar la App. */
  error: string | null
  checkedAt: string | null
  installations: GitHubInstallation[]
  /** Los repositorios de Skynet sin archivar y su enlace con GitHub. */
  repositories: GitHubRepositoryLink[]
}

export interface GitHubApp {
  slug: string
  name: string
  owner: string
  htmlUrl: string
}

export interface GitHubInstallation {
  id: number
  account: string
  accountType: string
  /** `all` (todos los repositorios de la cuenta) o `selected`. */
  repositorySelection: string
  suspendedAt: string | null
  syncedAt: string | null
  repositories: GitHubInstallationRepository[]
}

export interface GitHubInstallationRepository {
  id: number
  fullName: string
  private: boolean
}

/** `CONNECTED`: una instalación llega a él; `NOT_INSTALLED`: está en GitHub, pero no; `NOT_GITHUB`. */
export type GitHubAccess = 'CONNECTED' | 'NOT_INSTALLED' | 'NOT_GITHUB'

export interface GitHubRepositoryLink {
  id: string
  projectId: string
  projectKey: string
  name: string
  remoteUrl: string | null
  /** `owner/nombre` según su URL remota; null si no es de GitHub. */
  fullName: string | null
  access: GitHubAccess
  installationId: number | null
  installationAccount: string | null
  githubRepositoryId: number | null
}

export type WebhookDeliveryStatus = 'PENDING' | 'PROCESSED' | 'FAILED' | 'IGNORED'

/** Una entrega de webhook de GitHub, sin su cuerpo. */
export interface WebhookDelivery {
  deliveryId: string
  event: string
  action: string | null
  installationId: number | null
  /** `owner/nombre` del repositorio de GitHub, si la entrega es de uno. */
  repository: string | null
  status: WebhookDeliveryStatus
  attempts: number
  /** El error del último intento o por qué se ignoró. */
  error: string | null
  /** Eventos `github.*` que publicó. */
  events: number
  receivedAt: string
  processedAt: string | null
}

/** Los ajustes de los disparadores (DS): `GET /api/triggers/settings`. */
export interface TriggerSettings {
  /** El interruptor general: apagado, nada se lanza desde GitHub ni desde el cron. */
  enabled: boolean
  /** Quién puede lanzar desde GitHub, ya resuelto. */
  authorizedLogins: string[]
  /** Si son las cuentas de la instalación porque no se ha fijado nadie. */
  authorizedByDefault: boolean
  /** Repositorios de órdenes (`owner/nombre`), con el contrato `skynet-order`. */
  orderRepositories: string[]
  /** Ejecuciones que se pueden lanzar desde GitHub al día (0: ninguna). */
  dailyLimit: number
  /** Las lanzadas hoy, desde las 00:00 en Madrid. */
  launchedToday: number
  updatedAt: string | null
}

export type TriggerKind = 'ISSUE' | 'SCHEDULE' | 'DEPLOYMENT'

/** Lo propio de un disparador programado. */
export interface TriggerSchedule {
  /** Expresión cron de Spring, con segundos: `0 0 9 * * MON`. */
  cron: string
  timeZone: string
  workItemTitle: string
  /** El trabajo de sus ejecuciones, cuando ya se ha creado. */
  workItemId: string | null
  nextFireAt: string | null
  lastFiredAt: string | null
  lastOutcome: 'LAUNCHED' | 'SKIPPED' | 'REJECTED' | null
  lastReason: string | null
  lastRunId: string | null
}

/** Lo propio de un disparador por despliegue (DL-D). */
export interface TriggerDeployWatch {
  /** El entorno que vigila. */
  environment: string
  /** Al quedar sano o al fallar la salud (no sano o sin respuesta). */
  health: 'HEALTHY' | 'UNHEALTHY'
  workItemTitle: string
  workItemId: string | null
  lastFiredAt: string | null
  lastOutcome: 'LAUNCHED' | 'SKIPPED' | 'REJECTED' | null
  lastReason: string | null
  lastRunId: string | null
}

/** Un disparador de un repositorio: de GitHub, programado o por despliegue. */
export interface TriggerRule {
  id: string
  repositoryId: string
  kind: TriggerKind
  workflowKey: string
  /** La etiqueta que lo lanza; null en los programados. */
  label: string | null
  workItemType: WorkItemType
  inputs: Record<string, unknown>
  inputPolicy: Record<
    string,
    { type: string; maxLength: number | null; allowedValues: unknown[] | null }
  >
  enabled: boolean
  createdAt: string
  updatedAt: string
  schedule: TriggerSchedule | null
  deployment: TriggerDeployWatch | null
}

/** Para crear o cambiar un disparador; lo que no llegue toma su valor por defecto. */
export interface TriggerDraft {
  kind?: TriggerKind
  workflowKey?: string
  label?: string
  enabled?: boolean
  cron?: string
  timeZone?: string
  workItemTitle?: string
  environment?: string
  health?: TriggerDeployWatch['health']
  inputs?: Record<string, unknown>
}

export type TriggerLaunchStatus =
  'QUEUED' | 'LAUNCHED' | 'FINISHED' | 'DUPLICATE' | 'REJECTED' | 'DONE'

/** Una petición que llegó de GitHub: una etiqueta, un comentario `/skynet` o una revisión. */
export interface TriggerLaunch {
  id: string
  deliveryId: string
  /** `owner/nombre` de la issue o la PR. */
  source: string
  issueNumber: number
  issueUrl: string | null
  label: string
  mode: 'ISSUE' | 'ORDER' | 'COMMAND'
  /** La primera línea de la orden `/skynet`, si la hubo. */
  command: string | null
  pullRequest: boolean
  author: string | null
  sender: string | null
  title: string
  status: TriggerLaunchStatus
  reason: string | null
  triggerId: string | null
  repositoryId: string | null
  workItemId: string | null
  workflowRunId: string | null
  noticePending: boolean
  noticeError: string | null
  createdAt: string
  updatedAt: string
}

/** La cola de ejecuciones de un repositorio (DS-C). */
export interface RunQueue {
  repositoryId: string
  /** El límite que se aplica. */
  maxConcurrentRuns: number
  /** Si el repositorio tiene el suyo; si no, es el general (`defaultLimit`). */
  custom: boolean
  defaultLimit: number
  /** Las que ocupan un hueco ahora. */
  running: number
  /** Las que esperan, de la primera a la última. */
  queued: string[]
}

/** El sitio de una ejecución en la cola de su repositorio; `position` 0 si no está en cola. */
export interface RunQueuePosition {
  workflowRunId: string
  repositoryId: string | null
  position: number
  running: number
  maxConcurrentRuns: number
}

/** Qué enseña una lista respecto a lo archivado: lo vigente (por defecto), lo archivado o todo. */
export type ArchivedFilter = 'false' | 'true' | 'all'

/** Estado de un despliegue (DL): cómo terminó, aparte de si responde. */
export type DeploymentStatus = 'PENDING' | 'IN_PROGRESS' | 'SUCCEEDED' | 'FAILED' | 'SUPERSEDED'

/** Salud de lo desplegado (DL-C): la comprueba Skynet con la URL que configura el admin. */
export type DeploymentHealth = 'UNKNOWN' | 'CHECKING' | 'HEALTHY' | 'UNHEALTHY' | 'TIMEOUT'

/** Un despliegue de un repositorio en un entorno (DL-A). */
export interface Deployment {
  id: string
  repositoryId: string
  environment: string
  /** El id de GitHub Deployments. */
  externalId: string
  commitSha: string
  ref: string | null
  pullRequestNumber: number | null
  /** La URL que mandó el listener; solo se enseña, la salud usa la configurada. */
  url: string | null
  creator: string | null
  status: DeploymentStatus
  health: DeploymentHealth
  error: string | null
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  statusAt: string | null
  /** El despliegue que lo sustituyó. */
  supersededBy: string | null
  createdAt: string
  updatedAt: string
  healthDetail: string | null
  healthCheckedAt: string | null
  healthAttempts: number
  /** Cuándo se marcó atascado; null si no lo está. */
  stuckAt: string | null
}

/** Un entorno: lo desplegado ahora y el último despliegue pedido. */
export interface DeploymentEnvironment {
  environment: string
  deployed: Deployment | null
  latest: Deployment | null
}

/** Un estado recibido; `applied` falso si llegó tarde o el despliegue ya estaba sustituido. */
export interface DeploymentStatusRecord {
  id: string
  externalId: string
  state: DeploymentStatus
  description: string | null
  occurredAt: string
  applied: boolean
  receivedAt: string
}

export type HealthProbeOutcome = 'OK' | 'BAD_STATUS' | 'SHA_MISMATCH' | 'TIMEOUT' | 'ERROR'

/** Un intento de la comprobación de salud: la evidencia. */
export interface HealthProbe {
  attempt: number
  url: string
  outcome: HealthProbeOutcome
  statusCode: number | null
  latencyMs: number | null
  detail: string | null
  checkedAt: string
}

export interface DeploymentDetail {
  deployment: Deployment
  statuses: DeploymentStatusRecord[]
  probes: HealthProbe[]
}

/** La comprobación de salud de un entorno (DL-C). */
export interface HealthCheck {
  repositoryId: string
  environment: string
  url: string
  timeoutSeconds: number
  attempts: number
  intervalSeconds: number
  initialDelaySeconds: number
  expectSha: boolean
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export type HealthCheckDraft = Partial<
  Pick<
    HealthCheck,
    | 'url'
    | 'timeoutSeconds'
    | 'attempts'
    | 'intervalSeconds'
    | 'initialDelaySeconds'
    | 'expectSha'
    | 'enabled'
  >
>

/** Lo que hizo una reconciliación con GitHub. */
export interface DeploymentReconciled {
  repositoryId: string
  deployments: number
  statuses: number
  changed: number
}

/** Algo que se puede archivar, restaurar y eliminar. */
export type ArchiveTarget =
  | { kind: 'project'; id: string }
  | { kind: 'repository'; id: string; projectId: string }
  | { kind: 'work-item'; id: string }
  | { kind: 'run'; id: string }
  | { kind: 'runner'; id: string }
  /** `id` es la clave del workflow. */
  | { kind: 'workflow'; id: string }

export interface ArchiveState {
  id: string
  archivedAt: string | null
}

/** Lo que se borraría al eliminar algo y lo que lo impide (GET …/deletion-preview). */
export interface DeletionPreview {
  deletable: boolean
  blockers: string[]
  warnings: string[]
  /** Worktrees que siguen en su runner y solo usa lo que se elimina. */
  liveWorkspaces: number
  counts: DeletionCounts
}

export interface DeletionCounts {
  repositories: number
  workItems: number
  runs: number
  agents: number
  artifacts: number
  artifactBytes: number
  events: number
}

/** Ruta de la API de lo que se archiva o elimina. */
export function targetPath(target: ArchiveTarget): string {
  switch (target.kind) {
    case 'project':
      return `/api/projects/${target.id}`
    case 'repository':
      return `/api/projects/${target.projectId}/repositories/${target.id}`
    case 'work-item':
      return `/api/work-items/${target.id}`
    case 'run':
      return `/api/workflow-runs/${target.id}`
    case 'runner':
      return `/api/runners/${target.id}`
    case 'workflow':
      return `/api/workflows/${encodeURIComponent(target.id)}`
  }
}

/** `?archived=…` para una lista; nada con el valor por defecto. */
const archivedQuery = (archived?: ArchivedFilter) =>
  archived && archived !== 'false' ? `?archived=${archived}` : ''

/** `?a=1&b=2` con los parámetros que tienen valor; nada si no tiene ninguno. */
const query = (params: Record<string, string | undefined>) => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v != null) p.set(k, v)
  const s = p.toString()
  return s ? `?${s}` : ''
}

export class ApiError extends Error {
  readonly status: number
  /** Problemas de un YAML rechazado (p. ej. al publicar un borrador con errores). */
  readonly problems: Problem[]

  constructor(status: number, message: string, problems: Problem[] = []) {
    super(message)
    this.status = status
    this.problems = problems
  }
}

export interface Session {
  username: string
}

/** Clave de la sesión en la caché de TanStack Query. */
export const SESSION_KEY = ['session']

/** Evento de `window` cuando la API responde 401: la sesión ha caducado o se ha cerrado. */
export const UNAUTHORIZED_EVENT = 'skynet:unauthorized'

/** Token CSRF que el servidor deja en la cookie `XSRF-TOKEN`; va en cada petición que cambia algo. */
function csrfToken(): string | null {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie)
  return match ? decodeURIComponent(match[1]) : null
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const csrf = method === 'GET' ? null : csrfToken()
  if (csrf) headers['X-XSRF-TOKEN'] = csrf
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  // El login fallido también es un 401, pero no es una sesión caducada.
  if (res.status === 401 && path !== '/api/auth/login') {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    let problems: Problem[] = []
    try {
      const problem = (await res.json()) as {
        detail?: string
        title?: string
        problems?: Problem[]
      }
      message = problem.detail ?? problem.title ?? message
      problems = problem.problems ?? []
    } catch {
      // Respuesta sin cuerpo JSON.
    }
    throw new ApiError(res.status, message, problems)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** Lee `limit` bytes de un artefacto desde `offset` (el servidor sirve 8 MiB como mucho). */
async function artifactChunk(id: string, offset: number, limit: number): Promise<ArtifactChunk> {
  const res = await fetch(`/api/artifacts/${id}/content?offset=${offset}&limit=${limit}`)
  if (res.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`)
  const bytes = new Uint8Array(await res.arrayBuffer())
  return { bytes, size: Number(res.headers.get('X-Artifact-Size') ?? bytes.length), offset }
}

export const api = {
  session: () => request<Session>('GET', '/api/auth/session'),
  appearance: () => request<Appearance>('GET', '/api/settings/appearance'),
  saveAppearance: (colors: Appearance['colors']) =>
    request<Appearance>('PUT', '/api/settings/appearance', { colors }),
  saveIconPack: (icons: IconPack) =>
    request<Appearance>('PUT', '/api/settings/appearance', { icons }),
  gitHub: () => request<GitHubStatus>('GET', '/api/github'),
  syncGitHub: () => request<GitHubStatus>('POST', '/api/github/sync'),
  gitHubDeliveries: (limit = 50) =>
    request<WebhookDelivery[]>('GET', `/api/github/deliveries?limit=${limit}`),
  triggerSettings: () => request<TriggerSettings>('GET', '/api/triggers/settings'),
  saveTriggerSettings: (
    body: Partial<
      Pick<TriggerSettings, 'enabled' | 'authorizedLogins' | 'orderRepositories' | 'dailyLimit'>
    >,
  ) => request<TriggerSettings>('PUT', '/api/triggers/settings', body),
  triggerLaunches: (limit = 20, repositoryId?: string) =>
    request<TriggerLaunch[]>(
      'GET',
      `/api/triggers/launches?limit=${limit}${repositoryId ? `&repositoryId=${repositoryId}` : ''}`,
    ),
  repositoryTriggers: (repositoryId: string) =>
    request<TriggerRule[]>('GET', `/api/repositories/${repositoryId}/triggers`),
  createTrigger: (repositoryId: string, body: TriggerDraft) =>
    request<TriggerRule>('POST', `/api/repositories/${repositoryId}/triggers`, body),
  updateTrigger: (id: string, body: TriggerDraft) =>
    request<TriggerRule>('PUT', `/api/triggers/${id}`, body),
  deleteTrigger: (id: string) => request<void>('DELETE', `/api/triggers/${id}`),
  fireTrigger: (id: string) => request<TriggerRule>('POST', `/api/triggers/${id}/fire`),
  runQueue: (repositoryId: string) =>
    request<RunQueue>('GET', `/api/repositories/${repositoryId}/run-queue`),
  saveRunQueue: (repositoryId: string, maxConcurrentRuns: number | null) =>
    request<RunQueue>('PUT', `/api/repositories/${repositoryId}/run-queue`, { maxConcurrentRuns }),
  runQueuePosition: (runId: string) =>
    request<RunQueuePosition>('GET', `/api/workflow-runs/${runId}/queue`),
  deployments: (repositoryId: string, limit = 20) =>
    request<Deployment[]>('GET', `/api/repositories/${repositoryId}/deployments?limit=${limit}`),
  deploymentEnvironments: (repositoryId: string) =>
    request<DeploymentEnvironment[]>('GET', `/api/repositories/${repositoryId}/environments`),
  deployment: (id: string) => request<DeploymentDetail>('GET', `/api/deployments/${id}`),
  recheckDeployment: (id: string) =>
    request<Deployment>('POST', `/api/deployments/${id}/health-check`),
  reconcileDeployments: (repositoryId: string) =>
    request<DeploymentReconciled>(
      'POST',
      `/api/repositories/${repositoryId}/deployments/reconcile`,
    ),
  healthChecks: (repositoryId: string) =>
    request<HealthCheck[]>('GET', `/api/repositories/${repositoryId}/health-checks`),
  saveHealthCheck: (repositoryId: string, environment: string, body: HealthCheckDraft) =>
    request<HealthCheck>(
      'PUT',
      `/api/repositories/${repositoryId}/health-checks/${encodeURIComponent(environment)}`,
      body,
    ),
  deleteHealthCheck: (repositoryId: string, environment: string) =>
    request<void>(
      'DELETE',
      `/api/repositories/${repositoryId}/health-checks/${encodeURIComponent(environment)}`,
    ),
  login: (username: string, password: string) =>
    request<Session>('POST', '/api/auth/login', { username, password }),
  logout: () => request<void>('POST', '/api/auth/logout'),
  revokeRunner: (id: string) => request<void>('POST', `/api/runners/${id}/revoke`),
  projects: (archived?: ArchivedFilter) =>
    request<Project[]>('GET', `/api/projects${archivedQuery(archived)}`),
  project: (id: string) => request<Project>('GET', `/api/projects/${id}`),
  createProject: (body: { key: string; name: string; description?: string }) =>
    request<Project>('POST', '/api/projects', body),
  /** Proyecto y repositorio a la vez, desde una entrada del catálogo (IP-B). */
  importProject: (body: ImportProjectRequest) =>
    request<{ project: Project; repository: Repository }>('POST', '/api/projects/import', body),
  keySuggestion: (name: string) =>
    request<{ key: string }>(
      'GET',
      `/api/projects/key-suggestion?name=${encodeURIComponent(name)}`,
    ),
  repositoryCatalog: () => request<RunnerCatalog[]>('GET', '/api/repository-catalog'),
  /** «Volver a buscar»: los runners buscan en su siguiente latido. */
  refreshRepositoryCatalog: () => request<void>('POST', '/api/repository-catalog/refresh'),
  repositories: (projectId: string, archived?: ArchivedFilter) =>
    request<Repository[]>(
      'GET',
      `/api/projects/${projectId}/repositories${archivedQuery(archived)}`,
    ),
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
    body: {
      validationCommand: string | null
      testReportPaths?: string[]
      /** Sin ellas, las rutas protegidas se quedan como estaban. */
      protectedPaths?: string[]
      /** Sin ellas, las reglas de riesgo se quedan como estaban (AU-A). */
      riskPaths?: string[]
      riskPatterns?: string[]
      /** Sin él, el interruptor se queda como estaba (AU-C). */
      autoMerge?: boolean
    },
  ) =>
    request<Repository>(
      'PUT',
      `/api/projects/${projectId}/repositories/${repositoryId}/verification`,
      body,
    ),
  /** Da al repositorio una política propia para sus agentes. */
  configureAgentPolicy: (
    projectId: string,
    repositoryId: string,
    body: Omit<AgentPolicy, 'maxBudgetUsd' | 'timeoutMinutes'> & {
      maxBudgetUsd: number
      timeoutMinutes: number
    },
  ) =>
    request<Repository>(
      'PUT',
      `/api/projects/${projectId}/repositories/${repositoryId}/agent-policy`,
      body,
    ),
  /** El repositorio vuelve a la política global. */
  inheritAgentPolicy: (projectId: string, repositoryId: string) =>
    request<Repository>(
      'DELETE',
      `/api/projects/${projectId}/repositories/${repositoryId}/agent-policy`,
    ),
  workItems: (projectId: string, archived?: ArchivedFilter) =>
    request<WorkItem[]>('GET', `/api/projects/${projectId}/work-items${archivedQuery(archived)}`),
  workItem: (id: string) => request<WorkItem>('GET', `/api/work-items/${id}`),
  createWorkItem: (
    projectId: string,
    body: { title: string; description?: string; type: WorkItemType; repositoryId?: string },
  ) => request<WorkItem>('POST', `/api/projects/${projectId}/work-items`, body),
  workItemSummary: (id: string) => request<WorkItemSummary>('GET', `/api/work-items/${id}/summary`),
  /** Trabajos de todos los proyectos en esas fases; sin fases, los no terminados. */
  pendingWorkItems: (query: { phase?: WorkPhase[]; projectId?: string } = {}) => {
    const params = new URLSearchParams()
    query.phase?.forEach((p) => params.append('phase', p))
    if (query.projectId) params.set('projectId', query.projectId)
    const qs = params.toString()
    return request<WorkItemSummary[]>('GET', `/api/work-items${qs ? `?${qs}` : ''}`)
  },
  projectOverview: (id: string) => request<ProjectOverview>('GET', `/api/projects/${id}/overview`),
  projectChanges: (id: string) => request<ChangeView[]>('GET', `/api/projects/${id}/changes`),
  results: () => request<ChangeView[]>('GET', '/api/results'),
  runs: (workItemId: string, archived?: ArchivedFilter) =>
    request<Run[]>('GET', `/api/work-items/${workItemId}/runs${archivedQuery(archived)}`),
  run: (id: string) => request<Run>('GET', `/api/workflow-runs/${id}`),
  pullRequests: (runId: string) =>
    request<PullRequestView[]>('GET', `/api/workflow-runs/${runId}/pull-requests`),
  ciChecks: (runId: string) =>
    request<CiCheckView[]>('GET', `/api/workflow-runs/${runId}/ci-checks`),
  escapes: (runId: string) => request<EscapeView[]>('GET', `/api/workflow-runs/${runId}/escapes`),
  markEscape: (runId: string, note: string) =>
    request<EscapeView>('POST', `/api/workflow-runs/${runId}/escapes`, { note }),
  removeEscape: (id: string) => request<void>('DELETE', `/api/escapes/${id}`),
  merges: (runId: string) => request<MergeView[]>('GET', `/api/workflow-runs/${runId}/merges`),
  assumptions: (runId: string) =>
    request<Assumption[]>('GET', `/api/workflow-runs/${runId}/assumptions`),
  findings: (runId: string) =>
    request<FindingView[]>('GET', `/api/workflow-runs/${runId}/findings`),
  judgeStats: (days: number, category?: FindingCategory) =>
    request<JudgeStatsRow[]>(
      'GET',
      `/api/factory/judges?days=${days}${category ? `&category=${category}` : ''}`,
    ),
  evalBank: (repositoryId?: string) =>
    request<EvalBank>(
      'GET',
      `/api/factory/evals${repositoryId ? `?repositoryId=${repositoryId}` : ''}`,
    ),
  runEvalCase: (runId: string) =>
    request<EvalCase[]>('GET', `/api/workflow-runs/${runId}/eval-case`),
  addEvalCase: (runId: string, expected: EvalExpected, note?: string) =>
    request<EvalCase>('POST', `/api/workflow-runs/${runId}/eval-case`, { expected, note }),
  removeEvalCase: (id: string) => request<void>('DELETE', `/api/eval-cases/${id}`),
  evalJudges: (caseId: string) =>
    request<EvalJudgeOption[]>('GET', `/api/eval-cases/${caseId}/judges`),
  repeatEvalCase: (caseId: string, judges: EvalJudgeChoice[]) =>
    request<EvalRunView[]>('POST', `/api/eval-cases/${caseId}/runs`, { judges }),
  repeatEvalBank: (repositoryId: string, judges: EvalJudgeChoice[]) =>
    request<EvalRunView[]>('POST', '/api/factory/evals/runs', { repositoryId, judges }),
  lessons: (status?: LessonStatus) =>
    request<Lesson[]>('GET', `/api/factory/lessons${status ? `?status=${status}` : ''}`),
  projectLessons: (projectId: string, status?: LessonStatus) =>
    request<Lesson[]>(
      'GET',
      `/api/projects/${projectId}/lessons${status ? `?status=${status}` : ''}`,
    ),
  lesson: (id: string) => request<LessonDetail>('GET', `/api/lessons/${id}`),
  applyLesson: (id: string, text: string) =>
    request<Lesson>('POST', `/api/lessons/${id}/apply`, { text }),
  dismissLesson: (id: string, reason: string) =>
    request<Lesson>('POST', `/api/lessons/${id}/dismiss`, { reason }),
  decideFinding: (id: string, outcome: 'aceptado' | 'irrelevante' | 'abierto', reason?: string) =>
    request<FindingView>('POST', `/api/findings/${id}/outcome`, { outcome, reason }),
  listRuns: (query: {
    status?: RunStatus[]
    projectId?: string
    since?: string
    q?: string
    archived?: ArchivedFilter
    page?: number
    size?: number
  }) => {
    const params = new URLSearchParams()
    query.status?.forEach((s) => params.append('status', s))
    if (query.projectId) params.set('projectId', query.projectId)
    if (query.since) params.set('since', query.since)
    if (query.q) params.set('q', query.q)
    if (query.archived && query.archived !== 'false') params.set('archived', query.archived)
    if (query.page !== undefined) params.set('page', String(query.page))
    if (query.size !== undefined) params.set('size', String(query.size))
    return request<RunPage>('GET', `/api/workflow-runs?${params}`)
  },
  launchRun: (workItemId: string, body: LaunchRequest) =>
    request<Run>('POST', `/api/work-items/${workItemId}/runs`, body),
  effectivePolicy: (definitionId: string, repositoryId: string) =>
    request<StagePolicy[]>(
      'GET',
      `/api/workflow-versions/${definitionId}/effective-policy?repositoryId=${repositoryId}`,
    ),
  agent: (id: string) => request<AgentRunDetail>('GET', `/api/agent-runs/${id}`),
  /** Cancela la ejecución: sus fases sin empezar y sus agentes en marcha. */
  cancelRun: (id: string) => request<Run>('POST', `/api/workflow-runs/${id}/cancel`),
  /** «Reintentar fase»: la ejecución fallida o cancelada sigue desde `stageKey`. */
  retryStage: (id: string, stageKey: string) =>
    request<Run>('POST', `/api/workflow-runs/${id}/stages/${encodeURIComponent(stageKey)}/retry`),
  /** «Reintentar ahora»: la fase que espera a la cuota arranca ya (CQ-A). */
  skipQuotaWait: (id: string, stageKey: string) =>
    request<Run>(
      'POST',
      `/api/workflow-runs/${id}/stages/${encodeURIComponent(stageKey)}/skip-quota-wait`,
    ),
  quotaSettings: () => request<QuotaSettings>('GET', '/api/settings/quota'),
  saveQuotaSettings: (
    changes: Partial<
      Pick<QuotaSettings, 'enabled' | 'maxWaitHours' | 'maxWaits' | 'fallbackAfterMinutes'>
    >,
  ) => request<QuotaSettings>('PUT', '/api/settings/quota', changes),
  quotaWaits: () => request<QuotaWaitRow[]>('GET', '/api/quota/waits'),
  cancelAgent: (id: string) => request<AgentRunDetail>('POST', `/api/agent-runs/${id}/cancel`),
  cleanupWorkspace: (id: string) =>
    request<AgentRunDetail>('POST', `/api/agent-runs/${id}/workspace/cleanup`),
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
  /** El artefacto entero como adjunto; la sesión va en la cookie, así que vale un enlace. */
  artifactDownloadUrl: (id: string) => `/api/artifacts/${id}/download`,
  /** Peticiones de aprobación, primero las pendientes; `status` admite varios. */
  approvals: (
    query: { status?: ApprovalStatus[]; projectId?: string; risk?: ApprovalRisk } = {},
  ) => {
    const params = new URLSearchParams()
    if (query.status?.length) params.set('status', query.status.join(','))
    if (query.projectId) params.set('projectId', query.projectId)
    if (query.risk) params.set('risk', query.risk)
    return request<ApprovalSummary[]>('GET', `/api/approvals?${params}`)
  },
  approval: (id: string) => request<ApprovalView>('GET', `/api/approvals/${id}`),
  /** Aprueba, rechaza, pide cambios o comenta. */
  decideApproval: (id: string, action: ApprovalAction, comment?: string) =>
    request<ApprovalView>('POST', `/api/approvals/${id}/decisions`, { action, comment }),
  /** Busca en la memoria del proyecto; sin texto, primero lo fijado y después lo más reciente. */
  memory: (projectId: string, q: MemoryQuery = {}) =>
    request<MemoryPage>(
      'GET',
      `/api/projects/${projectId}/memory${query({
        repository: q.repository,
        kind: q.kind,
        origin: q.origin,
        pinned: q.pinned == null ? undefined : String(q.pinned),
        archived: q.archived && q.archived !== 'false' ? q.archived : undefined,
        q: q.q || undefined,
        page: q.page ? String(q.page) : undefined,
        size: q.size == null ? undefined : String(q.size),
      })}`,
    ),
  memoryEntry: (id: string) => request<MemoryObservation>('GET', `/api/memory/${id}`),
  memoryHistory: (id: string) => request<MemoryRevision[]>('GET', `/api/memory/${id}/history`),
  /** Guarda una versión nueva con lo que cambie una persona. */
  editMemory: (id: string, content: MemoryContent) =>
    request<MemoryObservation>('PUT', `/api/memory/${id}`, content),
  /** Archivar, restaurar, fijar o desfijar una observación. */
  memoryAction: (id: string, action: 'archive' | 'restore' | 'pin' | 'unpin') =>
    request<MemoryObservation>('POST', `/api/memory/${id}/${action}`),
  agentMemory: (agentId: string) =>
    request<AgentMemory>('GET', `/api/agent-runs/${agentId}/memory`),
  /** Verificaciones del agente, la más reciente primero. */
  verifications: (agentId: string) =>
    request<VerificationResult[]>('GET', `/api/agent-runs/${agentId}/verifications`),
  /** Reejecuta solo la verificación del worktree del agente. */
  verify: (agentId: string) =>
    request<VerificationResult>('POST', `/api/agent-runs/${agentId}/verifications`),
  providers: () => request<ProviderKind[]>('GET', '/api/providers'),
  runners: (archived?: ArchivedFilter) =>
    request<Runner[]>('GET', `/api/runners${archivedQuery(archived)}`),
  archive: (target: ArchiveTarget) =>
    request<ArchiveState>('POST', `${targetPath(target)}/archive`),
  restore: (target: ArchiveTarget) =>
    request<ArchiveState>('POST', `${targetPath(target)}/restore`),
  deletionPreview: (target: ArchiveTarget) =>
    request<DeletionPreview>('GET', `${targetPath(target)}/deletion-preview`),
  /** Elimina lo archivado con todo lo que cuelga de ello; devuelve lo borrado. */
  delete: (target: ArchiveTarget) => request<DeletionCounts>('DELETE', targetPath(target)),
  /** Pide eliminar los worktrees que impiden eliminar un proyecto, trabajo o ejecución. */
  cleanupWorkspaces: (target: ArchiveTarget) =>
    request<{ requested: number }>('POST', `${targetPath(target)}/workspaces/cleanup`),
  dashboard: () => request<DashboardSummary>('GET', '/api/dashboard'),
  dashboardMetrics: (period: MetricsPeriod, tz: string) =>
    request<RunMetrics>(
      'GET',
      `/api/dashboard/metrics?period=${period}&tz=${encodeURIComponent(tz)}`,
    ),
  usageWindows: (runner?: string) =>
    request<UsageWindows>('GET', `/api/usage/windows${query({ runner })}`),
  usageHistory: (provider: string, bucket: string, window: QuotaWindowKind, runner?: string) =>
    request<UsageHistory>(
      'GET',
      `/api/usage/history${query({ provider, bucket, window, runner })}`,
    ),
  usageAgents: (period: MetricsPeriod, runner?: string) =>
    request<AgentUsage[]>('GET', `/api/usage/agents${query({ period, runner })}`),
  usageRuns: (period: MetricsPeriod, limit: number, runner?: string) =>
    request<RunUsage[]>('GET', `/api/usage/runs${query({ period, limit: String(limit), runner })}`),
  /** «Actualizar»: los runners consultan sus cuotas tras su siguiente latido. */
  refreshUsage: (runner?: string) =>
    request<{ runners: number }>('POST', `/api/usage/refresh${query({ runner })}`),
  workflowDefinitions: () => request<WorkflowDefinition[]>('GET', '/api/workflow-definitions'),
  workflows: (archived?: ArchivedFilter) =>
    request<WorkflowSummary[]>('GET', `/api/workflows${archivedQuery(archived)}`),
  workflow: (key: string) =>
    request<WorkflowView>('GET', `/api/workflows/${encodeURIComponent(key)}`),
  /** Crea un workflow con el YAML como borrador de su versión 1. */
  createWorkflow: (sourceYaml: string) =>
    request<DefinitionDetail>('POST', '/api/workflows', { sourceYaml }),
  /** Abre (o devuelve) el borrador de la versión siguiente. */
  draftWorkflow: (key: string) =>
    request<DefinitionDetail>('POST', `/api/workflows/${encodeURIComponent(key)}/draft`),
  /** Valida sin guardar; con `key` y `version`, como borrador de esa versión. */
  validateWorkflow: (body: { sourceYaml: string; key?: string; version?: number }) =>
    request<ValidationView>('POST', '/api/workflows/validate', body),
  /** JSON Schema del YAML, para el autocompletado del editor. */
  workflowSchema: () => request<object>('GET', '/api/workflows/schema'),
  workflowVersion: (id: string) => request<DefinitionDetail>('GET', `/api/workflow-versions/${id}`),
  saveWorkflowVersion: (id: string, sourceYaml: string, revision: number) =>
    request<DefinitionDetail>('PUT', `/api/workflow-versions/${id}`, { sourceYaml, revision }),
  publishWorkflowVersion: (id: string, revision: number) =>
    request<DefinitionDetail>('POST', `/api/workflow-versions/${id}/publish`, { revision }),
  /** Descarta un borrador; si era la única versión, el workflow desaparece. */
  discardWorkflowVersion: (id: string) => request<void>('DELETE', `/api/workflow-versions/${id}`),
  event: (sequence: number) => request<StoredEvent>('GET', `/api/events/${sequence}`),
  eventsBefore: (query: { workflowRunId?: string; before: number; limit?: number }) => {
    const params = new URLSearchParams({ before: String(query.before) })
    if (query.workflowRunId) params.set('workflowRunId', query.workflowRunId)
    if (query.limit !== undefined) params.set('limit', String(query.limit))
    return request<StoredEvent[]>('GET', `/api/events?${params}`)
  },
}
