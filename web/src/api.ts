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
  createdAt: string
  /** Cuándo se archivó; null si no lo está. */
  archivedAt: string | null
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
export type AgentFailure = 'lost' | 'start' | 'error' | 'timeout' | 'budget' | 'verification'

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
}

/** Una decisión o un comentario, que no se reescribe. */
export interface ApprovalDecision {
  id: string
  action: ApprovalAction | 'CANCEL'
  comment: string | null
  /** El usuario de la web, o `skynet` si se canceló con su ejecución. */
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
}

/** Una comprobación de fase: `artifacts`, `tests`, `diff`, `commit`, `scope` o `command`. */
export interface VerificationCheck {
  name: 'artifacts' | 'tests' | 'diff' | 'commit' | 'scope' | 'command'
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
}

/** Lanzamiento de un agente; los límites que falten usan los valores por defecto del servidor. */
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
  updatedAt: string | null
}

/** Qué enseña una lista respecto a lo archivado: lo vigente (por defecto), lo archivado o todo. */
export type ArchivedFilter = 'false' | 'true' | 'all'

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
  login: (username: string, password: string) =>
    request<Session>('POST', '/api/auth/login', { username, password }),
  logout: () => request<void>('POST', '/api/auth/logout'),
  revokeRunner: (id: string) => request<void>('POST', `/api/runners/${id}/revoke`),
  projects: (archived?: ArchivedFilter) =>
    request<Project[]>('GET', `/api/projects${archivedQuery(archived)}`),
  project: (id: string) => request<Project>('GET', `/api/projects/${id}`),
  createProject: (body: { key: string; name: string; description?: string }) =>
    request<Project>('POST', '/api/projects', body),
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
    body: { validationCommand: string | null; testReportPaths?: string[] },
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
    body: { title: string; description?: string; type: WorkItemType },
  ) => request<WorkItem>('POST', `/api/projects/${projectId}/work-items`, body),
  runs: (workItemId: string, archived?: ArchivedFilter) =>
    request<Run[]>('GET', `/api/work-items/${workItemId}/runs${archivedQuery(archived)}`),
  run: (id: string) => request<Run>('GET', `/api/workflow-runs/${id}`),
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
