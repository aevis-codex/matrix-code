export type ProjectOverview = {
  projectId: string;
  projectName: string;
  roles: string[];
  stages: string[];
  cacheHitRate: number;
  sessionTokens: number;
  currentStage: string;
};

export type RoleSummary = { name: string; state: string; todoCount: number; latestAction: string };
export type WorkbenchMetrics = {
  cacheHitRate: number;
  sessionTokens: number;
  eventCount: number;
  documentCount: number;
  openBugCount: number;
};
export type ModelProtocol = 'LOCAL' | 'OPENAI_COMPATIBLE';
export type ModelRole = 'PRODUCT' | 'DEVELOPER' | 'TESTER' | 'OPERATIONS';
export type RoleAgentConfig = {
  projectId: string;
  role: ModelRole;
  displayName: string;
  agentKind: string;
  providerId: string;
  model: string;
  toolContractVersion: string;
  cachePolicyId: string;
  volatileSuffixStrategy: string;
  cacheScopeStrategy: string;
  systemPrompt: string;
  userPromptTemplate: string;
  themeColor: string;
  fontFamily: string;
  fontSize: number;
  sortOrder: number;
  enabled: boolean;
  updatedAt: string;
};
export type RoleAgentConfigInput = Omit<RoleAgentConfig, 'projectId' | 'role' | 'updatedAt'>;
export type ProjectMember = {
  id: string;
  projectId: string;
  userId: string;
  roleKey: string;
  status: string;
  joinedAt: string;
  createdAt: string;
  updatedAt: string;
};
export type ProjectMemberInput = {
  userId: string;
  displayName: string;
  roleKey: string;
};
export type ProjectUserInput = {
  userId: string;
  username: string;
  displayName: string;
  email: string;
  password: string;
  roleKey: string;
};
export type ProjectMemberUpdateInput = {
  roleKey?: string;
  status?: string;
};
export type ProjectInvitation = {
  id: string;
  projectId: string;
  inviteeUserId: string;
  displayName: string;
  roleKey: string;
  status: string;
  createdByUserId: string;
  expiresAt: string;
  acceptedAt?: string | null;
  createdAt: string;
  updatedAt: string;
};
export type ProjectInvitationInput = {
  userId: string;
  displayName: string;
  roleKey: string;
  expiresAt: string;
};
export type ProjectInvitationReissueInput = {
  expiresAt: string;
};
export type IssuedProjectInvitation = {
  invitation: ProjectInvitation;
  token: string;
};
export type ProjectMemberBatchUpdateInput = {
  updates: Array<{ userId: string; roleKey?: string; status?: string }>;
};
export type ActorTokenIssueInput = {
  userId: string;
  ttlSeconds: number;
  bootstrapToken: string;
};
export type ActorPasswordLoginInput = {
  username: string;
  password: string;
};
export type ActorPasswordChangeInput = {
  oldPassword: string;
  newPassword: string;
};
export type ActorTokenIssueResponse = {
  userId: string;
  token: string;
  expiresAt: string;
};
export type ActorSessionResponse = {
  authenticated: boolean;
  userId: string;
};
export type ActorSessionInfo = {
  tokenFingerprint: string;
  deviceType: string;
  deviceId: string;
  createdAt: string;
  timeoutSeconds: number;
};
export type UserAuditRecord = {
  id: string;
  projectId: string;
  actorUserId: string;
  actorRole: string;
  actionKey: string;
  targetType: string;
  targetId: string;
  decision: string;
  summary: string;
  occurredAt: string;
};
export type ModelProvider = {
  id: string;
  name: string;
  protocol: ModelProtocol;
  baseUrl: string;
  apiKeySource: string;
  enabled: boolean;
};
export type RoleModelBinding = {
  projectId: string;
  role: ModelRole;
  providerId: string;
  model: string;
  currency: string;
  cacheHitPerMillion: number;
  cacheMissInputPerMillion: number;
  outputPerMillion: number;
  contextBudgetTokens: number;
  toolContractVersion: string;
};
export type ContextBlock = { type: string; summary: string; allowedByGate: boolean };
export type ContextManifest = { role: string; blocks: ContextBlock[]; omittedTypes: string[] };
export type UsageRecord = {
  roleSessionId: string;
  model: string;
  cacheHitTokens: number;
  cacheMissInputTokens: number;
  outputTokens: number;
  cacheHitRate: number;
  estimatedCost: number;
  currency: string;
  cacheSource?: 'PROVIDER' | 'ESTIMATED' | string;
  cacheScopeId?: string;
  stablePrefixHash?: string;
  providerUsageAvailable?: boolean;
  cachePolicyId?: string;
  volatileSuffixStrategy?: string;
  promptPartitionPolicyId?: string;
  promptPartitionFingerprint?: string;
  stablePartitionCount?: number;
  volatilePartitionCount?: number;
};
export type PromptContract = {
  role: ModelRole;
  model: string;
  toolContractVersion: string;
  systemPrefix: string;
  stablePrefixHash: string;
  estimatedStablePrefixTokens: number;
};
export type ModelGatewayMetrics = {
  requestCount: number;
  cacheHitTokens: number;
  cacheMissInputTokens: number;
  outputTokens: number;
  cacheHitRate: number;
  estimatedCost: number;
  currency: string;
  recentContextTypes: string[];
};
export type ModelRequestRecord = {
  requestId: string;
  projectId: string;
  role: ModelRole;
  providerId: string;
  model: string;
  answerSummary: string;
  actorUserId?: string;
  agentRunId?: string;
  usage: UsageRecord;
  contextTypes: string[];
  createdAt: string;
};
export type ModelCostTrendPoint = {
  requestId: string;
  createdAt: string;
  cacheHitRate: number;
  estimatedCost: number;
  currency: string;
  cacheSource: string;
};
export type ModelRunRequestPage = {
  projectId: string;
  agentRunId: string;
  page: number;
  size: number;
  total: number;
  metrics: ModelGatewayMetrics;
  trend: ModelCostTrendPoint[];
  requests: ModelRequestRecord[];
};
export type ModelCostBreakdown = {
  key: string;
  metrics: ModelGatewayMetrics;
};
export type ModelCostTrendBucket = {
  date: string;
  metrics: ModelGatewayMetrics;
};
export type ModelCostTrendReport = {
  projectId: string;
  days: number;
  timeZone: string;
  from: string;
  to: string;
  metrics: ModelGatewayMetrics;
  dailyTrend: ModelCostTrendBucket[];
  roleBreakdown: ModelCostBreakdown[];
  providerBreakdown: ModelCostBreakdown[];
  modelBreakdown: ModelCostBreakdown[];
};
export type ModelResponse = {
  requestId: string;
  answer: string;
  contextManifest: ContextManifest;
  usage: UsageRecord;
  binding: RoleModelBinding;
  promptContract: PromptContract;
  createdAt: string;
};
export type RoleModelRequestInput = {
  actorUserId?: string;
  instruction: string;
  contextBlocks: ContextBlock[];
  providerId?: string;
  model?: string;
  approvalMode?: string;
  reasoningEffort?: string;
  planMode?: boolean;
  goalMode?: boolean;
  tokenEconomy?: boolean;
};
export type ModelGatewaySummary = {
  bindings: RoleModelBinding[];
  metrics: ModelGatewayMetrics;
  recentRequests: ModelRequestRecord[];
};
export type WorkspaceStatus = 'AUTHORIZED' | 'REVOKED';
export type ApprovalDecision = 'ALLOW' | 'ASK' | 'DENY';
export type ExecutionTaskStatus =
  | 'APPROVAL_PENDING'
  | 'DENIED'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELED';
export type LocalTaskLogStream = 'STDOUT' | 'STDERR' | 'SYSTEM';
export type LocalTaskLog = {
  id: string;
  projectId: string;
  taskId: string;
  stream: LocalTaskLogStream;
  content: string;
  createdAt: string;
};
export type FileOperationType = 'LIST' | 'READ' | 'WRITE';
export type WorkspaceAuthorization = {
  id: string;
  projectId: string;
  name: string;
  rootPath: string;
  status: WorkspaceStatus;
  createdAt: string;
  lastAccessedAt: string;
};
export type DirectoryEntry = { name: string; relativePath: string; directory: boolean; sizeBytes: number };
export type FileOperationRecord = {
  id: string;
  projectId: string;
  workspaceId: string;
  type: FileOperationType;
  relativePath: string;
  status: string;
  summary: string;
  createdAt: string;
};
export type FileReadResult = { workspaceId: string; relativePath: string; content: string; sizeBytes: number };
export type FileWriteResult = { workspaceId: string; relativePath: string; bytesWritten: number };
export type ExecutionTask = {
  taskId: string;
  projectId: string;
  workspaceId: string;
  actorId: string;
  toolType: string;
  command: string;
  approvalDecision: ApprovalDecision;
  approverId: string;
  approvalNote: string;
  decidedAt: string | null;
  canceledBy: string;
  cancelNote: string;
  canceledAt: string | null;
  safetyRejectionReason: string;
  status: ExecutionTaskStatus;
  exitCode: number | null;
  stdoutSummary: string;
  stderrSummary: string;
  durationMillis: number;
  createdAt: string;
};
export type CodingAgentTaskStatus = 'PLANNED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELED';
export type CodingAgentStepType =
  | 'CONTEXT_RECALL'
  | 'PLAN_REVIEW'
  | 'FILE_REVIEW'
  | 'CODE_EDIT'
  | 'TEST_COMMAND'
  | 'DIFF_REVIEW'
  | 'HANDOFF';
export type CodingAgentExecutionStatus =
  | 'READY'
  | 'REVIEW_REQUIRED'
  | 'APPROVAL_REQUIRED'
  | 'SUBMITTED'
  | 'CAPTURED';
export type CodingAgentStep = {
  order: number;
  type: CodingAgentStepType;
  title: string;
  description: string;
  tool: string;
  requiresApproval: boolean;
};
export type CodingAgentTask = {
  taskId: string;
  projectId: string;
  role: ModelRole;
  goal: string;
  workspaceId: string;
  status: CodingAgentTaskStatus;
  createdAt: string;
  steps: CodingAgentStep[];
};
export type CodingAgentExecutionStep = {
  order: number;
  type: CodingAgentStepType;
  title: string;
  localTool: string;
  status: CodingAgentExecutionStatus;
  referenceId: string;
  summary: string;
};
export type CodingAgentExecutionPlan = {
  task: CodingAgentTask;
  executionSteps: CodingAgentExecutionStep[];
  testCommandTask: ExecutionTask;
  gitDiffSummary: GitDiffSummary;
};
export type CodingAgentExecutionInput = {
  goal: string;
  workspaceId: string;
  actorId: string;
  testCommand: string;
};
export type CodingAgentPatchInput = {
  workspaceId: string;
  actorId: string;
  runId?: string;
  relativePath: string;
  expectedContent: string;
  nextContent: string;
  summary: string;
  approved: boolean;
};
export type CodingAgentPatchResult = {
  projectId: string;
  role: ModelRole;
  workspaceId: string;
  actorId: string;
  runId: string;
  relativePath: string;
  summary: string;
  bytesWritten: number;
  gitDiffSummary: GitDiffSummary;
};
export type CodingAgentHandoffInput = {
  workspaceId: string;
  actorId: string;
  goal: string;
  relativePath: string;
  patchSummary: string;
  diffSummary: string;
  testTaskId: string;
  testTaskStatus: string;
  testCommand: string;
  deliveryConclusion: string;
};
export type LocalCommandApprovalInput = {
  actorId: string;
  decision: 'ALLOW' | 'DENY';
  note?: string;
};
export type LocalCommandCancelInput = {
  actorId: string;
  note?: string;
};
export type GitDiffSummary = {
  projectId: string;
  workspaceId: string;
  repository: boolean;
  changedFiles: string[];
  stat: string;
  capturedAt: string;
};
export type AuditRecord = {
  id: string;
  taskId: string;
  actorId: string;
  toolType: string;
  workspacePath: string;
  summary: string;
  decision: ApprovalDecision;
  occurredAt: string;
};
export type LocalExecutionSummary = {
  workspaces: WorkspaceAuthorization[];
  recentFileOperations: FileOperationRecord[];
  recentTasks: ExecutionTask[];
  activeTasks: ExecutionTask[];
  recentTaskLogs: LocalTaskLog[];
  recentGitDiff: GitDiffSummary | null;
  recentAuditRecords: AuditRecord[];
};
export type ModelGatewayConfig = {
  providers: ModelProvider[];
  bindings: RoleModelBinding[];
  metrics: ModelGatewayMetrics;
  recentRequests: ModelRequestRecord[];
};
export type BugSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'BLOCKER';
export type BugStatus = 'NEW' | 'CONFIRMED' | 'FIXING' | 'REGRESSION_PENDING' | 'CLOSED' | 'REOPENED';
export type DocumentType =
  | 'PRD'
  | 'ACCEPTANCE_CRITERIA'
  | 'UI_BRIEF'
  | 'IMPLEMENTATION_NOTE'
  | 'API_DOC'
  | 'DATABASE_SCRIPT'
  | 'DEPLOYMENT_DOC'
  | 'CODING_AGENT_HANDOFF'
  | 'QA_REPORT'
  | 'ACCEPTANCE_RECORD'
  | 'DEPLOYMENT_RECORD';
export type DocumentState = 'DRAFT' | 'REVIEW_PENDING' | 'FROZEN';
export type DeploymentTargetStatus = 'NOT_CONFIGURED' | 'APPROVAL_PENDING' | 'RECORDED' | 'RELEASE_READY' | 'DEPLOYED';
export type DeploymentHealthStatus = 'HEALTHY' | 'UNHEALTHY' | 'UNREACHABLE';
export type DeploymentOperationType = 'DEPLOYMENT' | 'ROLLBACK';
export type DeploymentOperationStatus = 'RECORDED' | 'SUCCEEDED' | 'FAILED';
export type ComposeEnvironmentStatus = 'CONFIGURED' | 'VALIDATED' | 'RUNNING' | 'STOPPED' | 'FAILED';
export type ComposeOperationType = 'VALIDATE' | 'START' | 'STOP' | 'LOGS';
export type ComposeOperationStatus = 'SUCCEEDED' | 'FAILED';
export type DocumentSummary = {
  id: string;
  type: DocumentType;
  title: string;
  content: string;
  version: number;
  state: DocumentState;
  createdAt: string;
};
export type ProjectBug = {
  id: string;
  projectId: string;
  title: string;
  severity: BugSeverity;
  status: BugStatus;
  steps: string;
  expected: string;
  actual: string;
  createdByRole: string;
  currentOwnerRole: string;
  lastNote: string;
  updatedAt: string;
};
export type DeploymentTarget = {
  id: string;
  projectId: string;
  environmentName: string;
  environmentUrl: string;
  sshAddress: string;
  deployNote: string;
  healthCheckUrl: string;
  rollbackNote: string;
  status: DeploymentTargetStatus;
  remoteExecuted: boolean;
  updatedAt: string;
};
export type DeploymentHealthCheck = {
  id: string;
  projectId: string;
  targetId: string;
  actorId: string;
  status: DeploymentHealthStatus;
  httpStatus: number | null;
  durationMillis: number;
  summary: string;
  checkedAt: string;
};
export type DeploymentOperationRecord = {
  id: string;
  projectId: string;
  targetId: string;
  actorId: string;
  type: DeploymentOperationType;
  status: DeploymentOperationStatus;
  note: string;
  createdAt: string;
};
export type DeploymentReleaseAuditImportInput = {
  actorId: string;
  sourceId: string;
  jsonLines: string[];
};
export type DeploymentReleaseAuditImportResult = {
  importedCount: number;
  skippedCount: number;
  records: DeploymentOperationRecord[];
};
export type ComposeEnvironment = {
  id: string;
  projectId: string;
  targetId: string;
  workspaceId: string;
  composeFilePath: string;
  projectName: string;
  serviceName: string;
  status: ComposeEnvironmentStatus;
  createdAt: string;
  updatedAt: string;
};
export type ComposeOperationRecord = {
  id: string;
  projectId: string;
  environmentId: string;
  actorId: string;
  type: ComposeOperationType;
  status: ComposeOperationStatus;
  summary: string;
  logExcerpt: string;
  createdAt: string;
};
export type DeploymentRuntimeSummary = {
  targetId: string;
  latestHealthCheck: DeploymentHealthCheck | null;
  latestDeploymentOperation: DeploymentOperationRecord | null;
  latestRollbackOperation: DeploymentOperationRecord | null;
};
export type ComposeRuntimeView = {
  environmentId: string;
  targetId: string;
  status: ComposeEnvironmentStatus;
  composeFilePath: string;
  projectName: string;
  serviceName: string;
  latestOperation: ComposeOperationRecord | null;
};
export type DeploymentOperationInput = {
  actorId: string;
  type: DeploymentOperationType;
  status: DeploymentOperationStatus;
  note: string;
};
export type ComposeEnvironmentInput = {
  workspaceId: string;
  composeFilePath: string;
  projectName: string;
  serviceName: string;
};
export type ComposeOperationInput = {
  actorId: string;
};
export type ProjectEvent = {
  id: string;
  projectId: string;
  type: string;
  message: string;
  occurredAt: string;
  sourceRole?: string;
  sourceId?: string;
};
export type AgentRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED';
export type AgentRunRecord = {
  id: string;
  projectId: string;
  roleKey: ModelRole;
  agentKind: string;
  actorUserId: string;
  providerId: string;
  modelName: string;
  status: AgentRunStatus;
  goal: string;
  summary: string;
  failureSummary?: string;
  retryable?: boolean;
  retryOfRunId?: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  updatedAt: string;
  claimedByUserId?: string | null;
  claimedAt?: string | null;
  claimExpiresAt?: string | null;
};
export type AgentRunEventRecord = {
  id: string;
  runId: string;
  projectId: string;
  eventType: string;
  eventTitle: string;
  eventPayload: string;
  occurredAt: string;
};
export type AgentRunRecoveryPlan = {
  sourceRunId: string;
  canRetry: boolean;
  blockedReason: string;
  recommendedAction: string;
  sourceRun?: AgentRunRecord | null;
};
export type AgentRunUserAuditEntry = {
  projectId: string;
  runId: string;
  userId: string;
  responsibleUserId: string;
  responsibilitySource: string;
  roleKey: ModelRole;
  agentKind: string;
  status: AgentRunStatus;
  actorUserId: string;
  claimedByUserId?: string | null;
  goal: string;
  summary: string;
  failureSummary?: string | null;
  eventCount: number;
  toolTraceCount: number;
  modelRequestCount: number;
  lastEventType?: string | null;
  lastEventTitle?: string | null;
  lastModelRequestId?: string | null;
  updatedAt: string;
};
export type AgentRunUserAuditReport = {
  projectId: string;
  userId: string;
  totalRuns: number;
  activeResponsibilities: number;
  modelRequestCount: number;
  entries: AgentRunUserAuditEntry[];
};
export type RuntimeNotificationLevel = 'ACTION' | 'SUCCESS' | 'WARNING' | 'ERROR';
export type RuntimeNotificationSourceType = 'APPROVAL' | 'LOCAL_TASK' | 'COMPOSE_OPERATION';
export type RuntimeNotification = {
  id: string;
  projectId?: string;
  level: RuntimeNotificationLevel;
  title: string;
  message: string;
  sourceType?: RuntimeNotificationSourceType;
  sourceId?: string;
  occurredAt: string;
  readAt?: string | null;
  readByUserId?: string;
};
export type RuntimeCheckStatus = 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED';
export type RuntimeCheckItem = {
  key: string;
  label: string;
  status: RuntimeCheckStatus;
  detail: string;
  blocking: boolean;
};
export type RuntimeDiagnosticsReport = {
  status: RuntimeCheckStatus;
  generatedAt: string;
  items: RuntimeCheckItem[];
  nextActions: string[];
};
export const runtimeSyncEventTypes = [
  'LOCAL_COMMAND_QUEUED',
  'LOCAL_COMMAND_STARTED',
  'LOCAL_COMMAND_COMPLETED',
  'LOCAL_COMMAND_FAILED',
  'LOCAL_COMMAND_CANCELED',
  'COMPOSE_ENVIRONMENT_CONFIGURED',
  'COMPOSE_OPERATION_RECORDED',
  'RUNTIME_NOTIFICATION_READ',
  'RUNTIME_NOTIFICATIONS_READ'
] as const;
export type RuntimeSyncEventType = (typeof runtimeSyncEventTypes)[number];
export type ProjectEventSubscription = { close: () => void };
export type ProjectWorkbench = {
  projectId: string;
  projectName: string;
  currentStage: string;
  roles: RoleSummary[];
  documents: DocumentSummary[];
  bugs: ProjectBug[];
  deploymentTargets: DeploymentTarget[];
  deploymentRuntimeSummaries: DeploymentRuntimeSummary[];
  composeEnvironments: ComposeEnvironment[];
  composeRuntimeViews: ComposeRuntimeView[];
  metrics: WorkbenchMetrics;
  modelGateway: ModelGatewaySummary;
  localExecution: LocalExecutionSummary;
  events: ProjectEvent[];
  runtimeNotifications?: RuntimeNotification[];
};

const defaultServerUrl = 'http://localhost:8080';
const actorTokenStorageKey = 'matrixcode.actorToken';

function matrixCodeServerUrl(): string {
  return import.meta.env.VITE_MATRIXCODE_SERVER_URL || defaultServerUrl;
}

function joinUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

function hasHeader(headers: Record<string, string>, headerName: string): boolean {
  return Object.keys(headers).some((name) => name.toLowerCase() === headerName.toLowerCase());
}

function headersToRecord(headersInit: HeadersInit | undefined): Record<string, string> {
  if (!headersInit) {
    return {};
  }

  if (headersInit instanceof Headers) {
    const headers: Record<string, string> = {};
    headersInit.forEach((value, name) => {
      headers[name] = value;
    });
    return headers;
  }

  if (Array.isArray(headersInit)) {
    return Object.fromEntries(headersInit);
  }

  return { ...headersInit };
}

function matrixCodeActorToken(): string {
  if (typeof window === 'undefined') {
    return '';
  }
  try {
    return window.localStorage.getItem(actorTokenStorageKey)?.trim() ?? '';
  } catch {
    return '';
  }
}

function actorHeaders(userId: string): Record<string, string> {
  const headers: Record<string, string> = { 'X-MatrixCode-User-Id': userId };
  const token = matrixCodeActorToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

function actorSessionHeaders(userId?: string): Record<string, string> {
  const headers: Record<string, string> = {};
  if (userId?.trim()) {
    headers['X-MatrixCode-User-Id'] = userId.trim();
  }
  const token = matrixCodeActorToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

function jsonHeaders(headersInit: HeadersInit | undefined, hasBody: boolean): Record<string, string> {
  const headers = headersToRecord(headersInit);
  if (!hasHeader(headers, 'Accept')) {
    headers.Accept = 'application/json';
  }
  if (hasBody && !hasHeader(headers, 'Content-Type')) {
    headers['Content-Type'] = 'application/json';
  }

  return headers;
}

async function responseErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: unknown };
    if (typeof body.message === 'string' && body.message.trim()) {
      return body.message;
    }
  } catch {
    return `团队服务器请求失败：${response.status} ${response.statusText}`.trim();
  }

  return `团队服务器请求失败：${response.status} ${response.statusText}`.trim();
}

async function requestJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const { headers: initHeaders, ...requestInit } = init;
  const headers = jsonHeaders(initHeaders, init.body !== undefined);
  let response: Response;

  try {
    response = await fetch(url, { ...requestInit, headers });
  } catch {
    throw new Error('团队服务器连接失败');
  }

  if (!response.ok) {
    throw new Error(await responseErrorMessage(response));
  }

  return (await response.json()) as T;
}

async function requestOptionalJson<T>(url: string, init: RequestInit = {}): Promise<T | null> {
  const { headers: initHeaders, ...requestInit } = init;
  const headers = jsonHeaders(initHeaders, init.body !== undefined);
  let response: Response;

  try {
    response = await fetch(url, { ...requestInit, headers });
  } catch {
    throw new Error('团队服务器连接失败');
  }

  if (!response.ok) {
    throw new Error(await responseErrorMessage(response));
  }
  if (response.status === 204) {
    return null;
  }

  return (await response.json()) as T;
}

type ServerSentEvent = {
  event: string;
  data: string;
};

/**
 * 从当前缓冲区切出一个完整 SSE 事件。
 * 作用域：模型流式 API 客户端；场景：兼容 LF 和 CRLF 分隔，避免网络分片导致事件被截断解析。
 */
function nextServerSentEvent(buffer: string): { rawEvent: string; rest: string } | null {
  const lfIndex = buffer.indexOf('\n\n');
  const crlfIndex = buffer.indexOf('\r\n\r\n');
  if (lfIndex === -1 && crlfIndex === -1) {
    return null;
  }
  if (crlfIndex !== -1 && (lfIndex === -1 || crlfIndex < lfIndex)) {
    return {
      rawEvent: buffer.slice(0, crlfIndex),
      rest: buffer.slice(crlfIndex + 4)
    };
  }
  return {
    rawEvent: buffer.slice(0, lfIndex),
    rest: buffer.slice(lfIndex + 2)
  };
}

/**
 * 解析单个 SSE 事件的事件名和 data 字段。
 * 作用域：模型流式 API 客户端；场景：把服务端 `event:delta` / `event:completed` 转成前端可分发结构。
 */
function parseServerSentEvent(rawEvent: string): ServerSentEvent {
  let event = 'message';
  const dataLines: string[] = [];
  rawEvent.split(/\r?\n/).forEach((line) => {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
  });
  return { event, data: dataLines.join('\n') };
}

/**
 * 将 SSE data 解析为指定 JSON 载荷。
 * 作用域：模型流式 API 客户端；场景：统一处理 delta、completed 和 error 事件的 JSON 反序列化错误。
 */
function parseModelStreamPayload<T>(event: ServerSentEvent): T {
  try {
    return JSON.parse(event.data) as T;
  } catch {
    throw new Error('模型流式响应格式不正确');
  }
}

function projectUrl(serverUrl: string, projectId: string, path: string): string {
  return joinUrl(serverUrl, `/api/projects/${encodeURIComponent(projectId)}${path}`);
}

function looksLikeServerUrl(value: string | undefined): boolean {
  return value?.startsWith('http://') || value?.startsWith('https://') || false;
}

function runtimeNotificationReadRequest(
  actorUserIdOrServerUrl?: string,
  serverUrl?: string
): { serverUrl: string; init: RequestInit } {
  if (!actorUserIdOrServerUrl || looksLikeServerUrl(actorUserIdOrServerUrl)) {
    return {
      serverUrl: actorUserIdOrServerUrl || serverUrl || matrixCodeServerUrl(),
      init: { method: 'POST' }
    };
  }

  return {
    serverUrl: serverUrl || matrixCodeServerUrl(),
    init: {
      method: 'POST',
      headers: actorHeaders(actorUserIdOrServerUrl),
      body: JSON.stringify({ actorUserId: actorUserIdOrServerUrl })
    }
  };
}

function actorScopedRequest(
  actorUserIdOrServerUrl?: string,
  serverUrl?: string
): { serverUrl: string; headers?: Record<string, string> } {
  // 兼容旧调用的 serverUrl 参数，同时让敏感读写操作可以显式携带当前操作者身份。
  if (!actorUserIdOrServerUrl || looksLikeServerUrl(actorUserIdOrServerUrl)) {
    return { serverUrl: actorUserIdOrServerUrl || serverUrl || matrixCodeServerUrl() };
  }

  return {
    serverUrl: serverUrl || matrixCodeServerUrl(),
    headers: actorHeaders(actorUserIdOrServerUrl)
  };
}

function eventStreamUrl(projectId: string, actorUserIdOrServerUrl?: string, serverUrl?: string): string {
  if (!actorUserIdOrServerUrl || looksLikeServerUrl(actorUserIdOrServerUrl)) {
    return projectUrl(actorUserIdOrServerUrl || serverUrl || matrixCodeServerUrl(), projectId, '/events/stream');
  }

  const params = new URLSearchParams({ actorUserId: actorUserIdOrServerUrl });
  const token = matrixCodeActorToken();
  if (token) {
    params.set('actorToken', token);
  }
  return `${projectUrl(serverUrl || matrixCodeServerUrl(), projectId, '/events/stream')}?${params.toString()}`;
}

/**
 * 读取项目顶部概览信息。
 * 作用域：项目成员可读；场景：工作台首页进入时展示阶段、角色和关键指标。
 */
export function loadProjectOverview(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectOverview> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectOverview>(projectUrl(request.serverUrl, projectId, '/overview'), {
    headers: request.headers
  });
}

/**
 * 读取项目工作台聚合视图。
 * 作用域：项目成员可读；场景：页面初始化、角色切换和业务操作后的主数据刷新。
 */
export function loadProjectWorkbench(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectWorkbench> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectWorkbench>(projectUrl(request.serverUrl, projectId, '/workbench'), {
    headers: request.headers
  });
}

/**
 * 标记单条运行态提醒已读。
 * 作用域：当前操作者；场景：用户处理审批、任务或 Compose 提醒后同步已读状态。
 */
export function markRuntimeNotificationRead(
  projectId: string,
  notificationId: string,
  actorUserIdOrServerUrl?: string,
  serverUrl?: string
): Promise<RuntimeNotification> {
  const request = runtimeNotificationReadRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<RuntimeNotification>(
    projectUrl(request.serverUrl, projectId, `/runtime-notifications/${encodeURIComponent(notificationId)}/read`),
    request.init
  );
}

/**
 * 批量标记当前用户运行态提醒已读。
 * 作用域：当前操作者；场景：提醒中心“一键已读”。
 */
export function markAllRuntimeNotificationsRead(
  projectId: string,
  actorUserIdOrServerUrl?: string,
  serverUrl?: string
): Promise<RuntimeNotification[]> {
  const request = runtimeNotificationReadRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<RuntimeNotification[]>(
    projectUrl(request.serverUrl, projectId, '/runtime-notifications/read-all'),
    request.init
  );
}

/**
 * 订阅项目实时事件流。
 * 作用域：项目成员；场景：通过 SSE 驱动工作台自动刷新、事件提示和多人协作状态同步。
 */
export function subscribeProjectEvents(
  projectId = 'demo',
  handlers: {
    onEvent: (event: ProjectEvent) => void;
    onError?: () => void;
    onUnsupported?: () => void;
  },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): ProjectEventSubscription {
  if (typeof EventSource === 'undefined') {
    handlers.onUnsupported?.();
    return { close: () => undefined };
  }

  const source = new EventSource(eventStreamUrl(projectId, actorUserIdOrServerUrl, serverUrl));
  const listeners = new Map<RuntimeSyncEventType, (event: MessageEvent<string>) => void>();

  runtimeSyncEventTypes.forEach((type) => {
    const listener = (event: MessageEvent<string>) => {
      try {
        handlers.onEvent(JSON.parse(event.data) as ProjectEvent);
      } catch {
      }
    };
    listeners.set(type, listener);
    source.addEventListener(type, listener);
  });

  source.onerror = () => handlers.onError?.();

  return {
    close: () => {
      listeners.forEach((listener, type) => source.removeEventListener(type, listener));
      source.close();
    }
  };
}

/**
 * 读取模型网关配置。
 * 作用域：项目成员；场景：配置中心展示供应商、角色绑定和模型预算。
 */
export function loadModelGatewayConfig(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ModelGatewayConfig> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ModelGatewayConfig>(projectUrl(request.serverUrl, projectId, '/model-gateway/config'), {
    headers: request.headers
  });
}

/**
 * 分页读取指定 Agent 运行关联的模型请求。
 * 作用域：项目成员；场景：运行中心按运行复盘 token、成本、缓存命中和上下文。
 */
export function loadAgentRunModelRequests(
  projectId: string,
  agentRunId: string,
  options: { page?: number; size?: number } = {},
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ModelRunRequestPage> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  const page = options.page ?? 0;
  const size = options.size ?? 20;
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return requestJson<ModelRunRequestPage>(
    projectUrl(
      request.serverUrl,
      projectId,
      `/model-gateway/agent-runs/${encodeURIComponent(agentRunId)}/model-requests?${query.toString()}`
    ),
    { headers: request.headers }
  );
}

/**
 * 读取项目级模型成本趋势。
 * 作用域：项目成员；场景：右侧指标和运行中心展示近 N 天成本、请求量和缓存效果。
 */
export function loadProjectModelCostTrends(
  projectId = 'demo',
  days = 30,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ModelCostTrendReport> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  const query = new URLSearchParams({ days: String(days) });
  return requestJson<ModelCostTrendReport>(
    projectUrl(request.serverUrl, projectId, `/model-gateway/cost-trends?${query.toString()}`),
    { headers: request.headers }
  );
}

/**
 * 读取角色智能体配置列表。
 * 作用域：项目成员；场景：配置中心展示每个角色的提示词、模型、样式和缓存策略。
 */
export function loadRoleAgentConfigs(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<RoleAgentConfig[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<RoleAgentConfig[]>(projectUrl(request.serverUrl, projectId, '/role-agent-configs'), {
    headers: request.headers
  });
}

/**
 * 读取项目成员列表。
 * 作用域：项目成员；场景：操作者选择、成员配置、权限判断和用户治理入口。
 */
export function loadProjectMembers(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectMember[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectMember[]>(projectUrl(request.serverUrl, projectId, '/identity/members'), {
    headers: request.headers
  });
}

/**
 * 添加普通项目成员。
 * 作用域：项目管理角色；场景：已有用户加入项目并获得角色权限。
 */
export function addProjectMember(
  projectId: string,
  input: ProjectMemberInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectMember> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectMember>(projectUrl(request.serverUrl, projectId, '/identity/members'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 创建可登录用户并授予项目角色。
 * 作用域：全局超级管理员 admin；场景：配置中心新增用户、设置初始密码和项目权限。
 */
export function createProjectUser(
  projectId: string,
  input: ProjectUserInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectMember> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectMember>(projectUrl(request.serverUrl, projectId, '/identity/users'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 更新单个项目成员角色或状态。
 * 作用域：项目管理角色；场景：禁用、恢复、移除成员或调整角色。
 */
export function updateProjectMember(
  projectId: string,
  userId: string,
  input: ProjectMemberUpdateInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectMember> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectMember>(
    projectUrl(request.serverUrl, projectId, `/identity/members/${encodeURIComponent(userId)}`),
    {
      method: 'PATCH',
      headers: request.headers,
      body: JSON.stringify(input)
    }
  );
}

/**
 * 读取项目邀请列表。
 * 作用域：项目管理角色；场景：成员配置页查看待处理、已撤销、已接受和过期邀请。
 */
export function loadProjectInvitations(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectInvitation[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectInvitation[]>(projectUrl(request.serverUrl, projectId, '/identity/invitations'), {
    headers: request.headers
  });
}

/**
 * 创建项目成员邀请。
 * 作用域：项目管理角色；场景：向未加入项目的用户发放一次性邀请令牌。
 */
export function createProjectInvitation(
  projectId: string,
  input: ProjectInvitationInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<IssuedProjectInvitation> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<IssuedProjectInvitation>(projectUrl(request.serverUrl, projectId, '/identity/invitations'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 接受项目邀请。
 * 作用域：当前登录用户；场景：被邀请用户用一次性令牌加入目标项目。
 */
export function acceptProjectInvitation(
  projectId: string,
  token: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectMember> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectMember>(
    projectUrl(request.serverUrl, projectId, `/identity/invitations/${encodeURIComponent(token)}/accept`),
    {
      method: 'POST',
      headers: request.headers
    }
  );
}

/**
 * 撤销项目邀请。
 * 作用域：项目管理角色；场景：邀请发错、成员不再需要加入或安全回收令牌。
 */
export function revokeProjectInvitation(
  projectId: string,
  invitationId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectInvitation> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectInvitation>(
    projectUrl(request.serverUrl, projectId, `/identity/invitations/${encodeURIComponent(invitationId)}/revoke`),
    {
      method: 'POST',
      headers: request.headers
    }
  );
}

/**
 * 重发项目邀请并轮换令牌。
 * 作用域：项目管理角色；场景：邀请过期、令牌泄露或需要延长有效期。
 */
export function reissueProjectInvitation(
  projectId: string,
  invitationId: string,
  input: ProjectInvitationReissueInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<IssuedProjectInvitation> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<IssuedProjectInvitation>(
    projectUrl(request.serverUrl, projectId, `/identity/invitations/${encodeURIComponent(invitationId)}/reissue`),
    {
      method: 'POST',
      headers: request.headers,
      body: JSON.stringify(input)
    }
  );
}

/**
 * 清理当前项目中过期的待处理邀请。
 * 作用域：项目管理角色；场景：成员配置页手动回收过期邀请状态。
 */
export function expireProjectInvitations(
  projectId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectInvitation[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectInvitation[]>(projectUrl(request.serverUrl, projectId, '/identity/invitations:expire'), {
    method: 'POST',
    headers: request.headers
  });
}

/**
 * 批量更新项目成员角色或状态。
 * 作用域：项目管理角色；场景：一次性治理多个成员，但仍由服务端保证至少保留管理成员。
 */
export function batchUpdateProjectMembers(
  projectId: string,
  input: ProjectMemberBatchUpdateInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectMember[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectMember[]>(projectUrl(request.serverUrl, projectId, '/identity/members:batch'), {
    method: 'PATCH',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 读取用户所属项目。
 * 作用域：用户本人或项目管理角色；场景：身份页展示用户可访问项目范围。
 */
export function loadUserProjects(
  projectId: string,
  userId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<string[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<string[]>(
    projectUrl(request.serverUrl, projectId, `/identity/users/${encodeURIComponent(userId)}/projects`),
    { headers: request.headers }
  );
}

/**
 * 读取用户身份审计记录。
 * 作用域：用户本人或项目管理角色；场景：身份页和安全审计查看登录、续期、踢下线等事件。
 */
export function loadUserAuditRecords(
  projectId: string,
  userId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<UserAuditRecord[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<UserAuditRecord[]>(
    projectUrl(request.serverUrl, projectId, `/identity/users/${encodeURIComponent(userId)}/audit-records`),
    { headers: request.headers }
  );
}

/**
 * 使用 bootstrap 凭证签发 actor token。
 * 作用域：部署初始化或受控工具链；场景：历史兼容和自动化环境签发短期身份令牌。
 */
export function issueActorToken(
  projectId: string,
  input: ActorTokenIssueInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ActorTokenIssueResponse> {
  return requestJson<ActorTokenIssueResponse>(projectUrl(serverUrl, projectId, '/identity/auth/actor-token'), {
    method: 'POST',
    body: JSON.stringify({ userId: input.userId, ttlSeconds: input.ttlSeconds }),
    headers: { 'X-MatrixCode-Bootstrap-Token': input.bootstrapToken }
  });
}

/**
 * 使用用户名和密码登录并获取 Sa-Token。
 * 作用域：可登录用户；场景：未登录页和配置中心身份页建立浏览器会话。
 */
export function loginActorSession(
  projectId: string,
  input: ActorPasswordLoginInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ActorTokenIssueResponse> {
  return requestJson<ActorTokenIssueResponse>(projectUrl(serverUrl, projectId, '/identity/auth/login'), {
    method: 'POST',
    body: JSON.stringify({ username: input.username, password: input.password })
  });
}

/**
 * 修改当前登录用户的密码。
 * 作用域：当前 Sa-Token 登录用户本人；场景：配置中心身份页完成账号安全维护。
 */
export async function changeActorPassword(
  projectId: string,
  input: ActorPasswordChangeInput,
  userId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<void> {
  await requestOptionalJson<unknown>(projectUrl(serverUrl, projectId, '/identity/auth/password'), {
    method: 'POST',
    body: JSON.stringify({ oldPassword: input.oldPassword, newPassword: input.newPassword }),
    headers: actorHeaders(userId)
  });
}

/**
 * 退出当前登录会话。
 * 作用域：当前操作者；场景：身份页主动退出并清理本地登录态。
 */
export async function logoutActorSession(
  projectId: string,
  userId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<void> {
  await requestOptionalJson<unknown>(projectUrl(serverUrl, projectId, '/identity/auth/logout'), {
    method: 'POST',
    headers: actorHeaders(userId)
  });
}

/**
 * 读取当前请求的登录态。
 * 作用域：当前操作者；场景：身份页检查 Sa-Token 是否仍有效。
 */
export function loadActorSession(
  projectId: string,
  userId?: string,
  serverUrl = matrixCodeServerUrl()
): Promise<ActorSessionResponse> {
  return requestJson<ActorSessionResponse>(projectUrl(serverUrl, projectId, '/identity/auth/session'), {
    headers: actorSessionHeaders(userId)
  });
}

/**
 * 续期当前 Sa-Token 会话。
 *
 * 该方法只处理当前登录用户自己的会话，身份来自显式 userId 和本地
 * Bearer token；服务端返回的 token 指纹用于展示，不暴露 token 明文。
 */
export function renewActorSession(
  projectId: string,
  userId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<ActorSessionInfo> {
  return requestJson<ActorSessionInfo>(projectUrl(serverUrl, projectId, '/identity/auth/session/renew'), {
    method: 'POST',
    headers: actorHeaders(userId)
  });
}

/**
 * 读取指定用户的 Sa-Token 会话列表。
 *
 * actorUserIdOrServerUrl 保持旧 API client 的重载兼容；传入操作者时，
 * 请求会携带操作者身份和本地 Bearer token，由服务端判断本人或项目
 * 管理角色是否有治理目标用户会话的权限。
 */
export function loadActorSessions(
  projectId: string,
  userId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ActorSessionInfo[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ActorSessionInfo[]>(
    projectUrl(request.serverUrl, projectId, `/identity/auth/users/${encodeURIComponent(userId)}/sessions`),
    { headers: request.headers }
  );
}

/**
 * 踢下线指定用户的所有 Sa-Token 会话。
 *
 * 前端只发起治理动作和身份透传，不在本地枚举或保存 token 明文；
 * 若踢下线的是当前用户，调用方负责清理本地登录态。
 */
export async function kickoutActorSessions(
  projectId: string,
  userId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<void> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  await requestOptionalJson<unknown>(
    projectUrl(request.serverUrl, projectId, `/identity/auth/users/${encodeURIComponent(userId)}/sessions/kickout`),
    {
      method: 'POST',
      headers: request.headers
    }
  );
}

/**
 * 读取运行诊断报告。
 * 作用域：项目成员；场景：诊断弹窗检查数据库、向量库、缓存和消息中间件状态。
 */
export function loadRuntimeDiagnostics(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<RuntimeDiagnosticsReport> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<RuntimeDiagnosticsReport>(projectUrl(request.serverUrl, projectId, '/runtime-diagnostics'), {
    headers: request.headers
  });
}

/**
 * 读取 Agent 运行列表。
 * 作用域：项目成员；场景：运行中心展示队列、执行、成功、失败和取消状态。
 */
export function loadAgentRuns(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<AgentRunRecord[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<AgentRunRecord[]>(projectUrl(request.serverUrl, projectId, '/agent-runs'), {
    headers: request.headers
  });
}

/**
 * 读取用户维度 Agent 运行审计。
 * 作用域：当前用户；场景：右侧运行指标展示当前操作者最近参与的 Agent 动作。
 */
export function loadAgentRunUserAudit(
  projectId: string,
  userId: string,
  limit = 50,
  serverUrl = matrixCodeServerUrl()
): Promise<AgentRunUserAuditReport> {
  const query = new URLSearchParams({ userId, limit: String(limit) }).toString();
  return requestJson<AgentRunUserAuditReport>(projectUrl(serverUrl, projectId, `/agent-runs/user-audit?${query}`), {
    headers: actorHeaders(userId)
  });
}

/**
 * 读取指定 Agent 运行事件。
 * 作用域：项目成员；场景：运行详情展示工具 trace、模型调用和生命周期事件。
 */
export function loadAgentRunEvents(
  projectId: string,
  runId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<AgentRunEventRecord[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<AgentRunEventRecord[]>(
    projectUrl(request.serverUrl, projectId, `/agent-runs/${encodeURIComponent(runId)}/events`),
    { headers: request.headers }
  );
}

/**
 * 读取失败 Agent 运行的恢复建议。
 * 作用域：项目成员；场景：运行中心判断是否可重试以及推荐处理动作。
 */
export function loadAgentRunRecoveryPlan(
  projectId: string,
  runId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<AgentRunRecoveryPlan> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<AgentRunRecoveryPlan>(
    projectUrl(request.serverUrl, projectId, `/agent-runs/${encodeURIComponent(runId)}/recovery-plan`),
    { headers: request.headers }
  );
}

/**
 * 重试失败的 Agent 运行。
 * 作用域：项目成员且操作者需通过服务端权限校验；场景：失败恢复入口重新排队执行。
 */
export function retryAgentRun(
  projectId: string,
  runId: string,
  actorUserId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<AgentRunRecord> {
  const query = new URLSearchParams({ actorUserId }).toString();
  return requestJson<AgentRunRecord>(
    projectUrl(serverUrl, projectId, `/agent-runs/${encodeURIComponent(runId)}/retry?${query}`),
    { method: 'POST', headers: actorHeaders(actorUserId) }
  );
}

/**
 * 认领指定 Agent 运行。
 * 作用域：项目成员；场景：开发者或 Worker 接手待执行任务，避免多人重复处理。
 */
export function claimAgentRun(
  projectId: string,
  runId: string,
  actorUserId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<AgentRunRecord> {
  const query = new URLSearchParams({ actorUserId }).toString();
  return requestJson<AgentRunRecord>(
    projectUrl(serverUrl, projectId, `/agent-runs/${encodeURIComponent(runId)}/claim?${query}`),
    { method: 'POST', headers: actorHeaders(actorUserId) }
  );
}

/**
 * 认领下一条可执行 Agent 运行。
 * 作用域：项目成员；场景：Worker 或运行中心“领取下一条”。
 */
export function claimNextAgentRun(
  projectId: string,
  actorUserId: string,
  serverUrl = matrixCodeServerUrl()
): Promise<AgentRunRecord | null> {
  const query = new URLSearchParams({ actorUserId }).toString();
  return requestOptionalJson<AgentRunRecord>(
    projectUrl(serverUrl, projectId, `/agent-runs/claim-next?${query}`),
    { method: 'POST', headers: actorHeaders(actorUserId) }
  );
}

/**
 * 更新角色智能体配置。
 * 作用域：项目管理角色；场景：配置提示词、模型预算、样式、缓存策略和工具契约。
 */
export function updateRoleAgentConfig(
  projectId: string,
  role: string,
  input: RoleAgentConfigInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<RoleAgentConfig> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<RoleAgentConfig>(
    projectUrl(request.serverUrl, projectId, `/role-agent-configs/${encodeURIComponent(role)}`),
    {
      method: 'PUT',
      headers: request.headers,
      body: JSON.stringify(input)
    }
  );
}

/**
 * 绑定角色默认模型和价格参数。
 * 作用域：项目管理角色；场景：为产品、开发、测试、运维指定供应商、模型和成本口径。
 */
export function bindRoleModel(
  projectId: string,
  role: string,
  input: {
    providerId: string;
    model: string;
    currency: string;
    cacheHitPerMillion: number;
    cacheMissInputPerMillion: number;
    outputPerMillion: number;
    contextBudgetTokens: number;
    toolContractVersion: string;
  },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<RoleModelBinding> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<RoleModelBinding>(projectUrl(request.serverUrl, projectId, `/roles/${encodeURIComponent(role)}/model-binding`), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 以指定角色发起模型请求。
 * 作用域：项目成员；场景：产品草稿、开发交付、测试报告或运维诊断调用角色智能体。
 */
export function createRoleModelRequest(
  projectId: string,
  role: string,
  input: RoleModelRequestInput,
  actorUserIdOrServerUrl = input.actorUserId ?? matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ModelResponse> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ModelResponse>(projectUrl(request.serverUrl, projectId, `/roles/${encodeURIComponent(role)}/model-requests`), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 以指定角色发起流式模型请求。
 * 作用域：项目成员；场景：Agent Composer 需要把大模型 delta 实时追加到输出台，同时在完成事件中拿到完整 usage。
 */
export async function createRoleModelRequestStream(
  projectId: string,
  role: string,
  input: RoleModelRequestInput,
  onDelta: (delta: string) => void,
  actorUserIdOrServerUrl = input.actorUserId ?? matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ModelResponse> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  let response: Response;
  try {
    response = await fetch(projectUrl(request.serverUrl, projectId, `/roles/${encodeURIComponent(role)}/model-requests/stream`), {
      method: 'POST',
      headers: jsonHeaders({ ...request.headers, Accept: 'text/event-stream' }, true),
      body: JSON.stringify(input)
    });
  } catch {
    throw new Error('团队服务器连接失败');
  }

  if (!response.ok) {
    throw new Error(await responseErrorMessage(response));
  }
  if (!response.body) {
    throw new Error('团队服务器未返回模型流式响应');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let completedResponse: ModelResponse | null = null;

  const handleEvent = (rawEvent: string) => {
    const event = parseServerSentEvent(rawEvent);
    if (!event.data.trim()) {
      return;
    }
    if (event.event === 'delta') {
      const payload = parseModelStreamPayload<{ delta?: unknown }>(event);
      if (typeof payload.delta === 'string' && payload.delta.length > 0) {
        onDelta(payload.delta);
      }
      return;
    }
    if (event.event === 'completed') {
      completedResponse = parseModelStreamPayload<ModelResponse>(event);
      return;
    }
    if (event.event === 'error') {
      const payload = parseModelStreamPayload<{ message?: unknown }>(event);
      throw new Error(typeof payload.message === 'string' && payload.message.trim() ? payload.message : '模型流式请求失败');
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (value) {
      buffer += decoder.decode(value, { stream: !done });
    }
    let next = nextServerSentEvent(buffer);
    while (next) {
      handleEvent(next.rawEvent);
      buffer = next.rest;
      next = nextServerSentEvent(buffer);
    }
    if (done) {
      buffer += decoder.decode();
      break;
    }
  }
  if (buffer.trim()) {
    handleEvent(buffer);
  }
  if (!completedResponse) {
    throw new Error('模型流式请求未返回完成事件');
  }
  return completedResponse;
}

/**
 * 授权本地工作区。
 * 作用域：项目成员；场景：允许编码智能体在指定目录内读取、写入和执行受控命令。
 */
export function authorizeLocalWorkspace(
  projectId: string,
  input: { name: string; rootPath: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<WorkspaceAuthorization> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<WorkspaceAuthorization>(projectUrl(request.serverUrl, projectId, '/local-execution/workspaces'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 读取本地执行摘要。
 * 作用域：项目成员；场景：运行中心展示工作区授权、命令队列、审批和最近日志。
 */
export function loadLocalExecutionSummary(
  projectId = 'demo',
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<LocalExecutionSummary> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<LocalExecutionSummary>(projectUrl(request.serverUrl, projectId, '/local-execution/summary'), {
    headers: request.headers
  });
}

/**
 * 列出授权工作区目录。
 * 作用域：项目成员；场景：开发面板浏览可供智能体引用或修改的文件。
 */
export function listLocalFiles(
  projectId: string,
  input: { workspaceId: string; relativePath: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DirectoryEntry[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DirectoryEntry[]>(projectUrl(request.serverUrl, projectId, '/local-execution/files/list'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 读取授权工作区文件内容。
 * 作用域：项目成员；场景：工作台预览代码、文档或配置文件正文。
 */
export function readLocalFile(
  projectId: string,
  input: { workspaceId: string; relativePath: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<FileReadResult> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<FileReadResult>(projectUrl(request.serverUrl, projectId, '/local-execution/files/read'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 写入授权工作区文件。
 * 作用域：当前操作者；场景：编码智能体应用受控 Patch 或保存人工编辑内容。
 */
export function writeLocalFile(
  projectId: string,
  input: { workspaceId: string; relativePath: string; content: string; actorId: string },
  serverUrl = matrixCodeServerUrl()
): Promise<FileWriteResult> {
  return requestJson<FileWriteResult>(projectUrl(serverUrl, projectId, '/local-execution/files/write'), {
    method: 'POST',
    headers: actorHeaders(input.actorId),
    body: JSON.stringify(input)
  });
}

/**
 * 提交本地命令执行任务。
 * 作用域：当前操作者；场景：编码智能体或开发者提交测试、构建、诊断命令等待审批。
 */
export function submitLocalCommand(
  projectId: string,
  input: { workspaceId: string; actorId: string; command: string; approvalMode?: string },
  serverUrl = matrixCodeServerUrl()
): Promise<ExecutionTask> {
  return requestJson<ExecutionTask>(projectUrl(serverUrl, projectId, '/local-execution/commands'), {
    method: 'POST',
    headers: actorHeaders(input.actorId),
    body: JSON.stringify(input)
  });
}

/**
 * 审批本地命令任务。
 * 作用域：当前操作者；场景：人工允许或拒绝高风险命令进入执行队列。
 */
export function decideLocalCommandApproval(
  projectId: string,
  taskId: string,
  input: LocalCommandApprovalInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ExecutionTask> {
  return requestJson<ExecutionTask>(
    projectUrl(serverUrl, projectId, `/local-execution/commands/${encodeURIComponent(taskId)}/approval`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 取消本地执行任务。
 * 作用域：当前操作者；场景：命令排队、运行或等待审批时由用户主动终止。
 */
export function cancelLocalExecutionTask(
  projectId: string,
  taskId: string,
  input: LocalCommandCancelInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ExecutionTask> {
  return requestJson<ExecutionTask>(
    projectUrl(serverUrl, projectId, `/local-execution/commands/${encodeURIComponent(taskId)}/cancel`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 读取本地任务日志。
 * 作用域：项目成员；场景：查看命令 stdout、stderr 和系统事件。
 */
export function loadLocalExecutionTaskLogs(
  projectId: string,
  taskId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<LocalTaskLog[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<LocalTaskLog[]>(
    projectUrl(request.serverUrl, projectId, `/local-execution/commands/${encodeURIComponent(taskId)}/logs`),
    { headers: request.headers }
  );
}

/**
 * 捕获授权工作区 Git diff 摘要。
 * 作用域：项目成员；场景：交付回溯、代码审查和受控 Patch 前后对比。
 */
export function captureGitDiff(
  projectId: string,
  input: { workspaceId: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<GitDiffSummary> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<GitDiffSummary>(projectUrl(request.serverUrl, projectId, '/local-execution/git-diff'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 生成编码智能体执行计划。
 * 作用域：当前操作者；场景：开发角色把目标、工作区和测试命令转为可审计执行计划。
 */
export function prepareCodingAgentExecution(
  projectId: string,
  role: string,
  input: CodingAgentExecutionInput,
  serverUrl = matrixCodeServerUrl()
): Promise<CodingAgentExecutionPlan> {
  return requestJson<CodingAgentExecutionPlan>(
    projectUrl(serverUrl, projectId, `/roles/${encodeURIComponent(role)}/coding-agent/execution-plans`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 应用编码智能体受控 Patch。
 * 作用域：当前操作者；场景：把智能体生成的文件变更写入授权工作区并记录 diff。
 */
export function applyCodingAgentPatch(
  projectId: string,
  role: string,
  input: CodingAgentPatchInput,
  serverUrl = matrixCodeServerUrl()
): Promise<CodingAgentPatchResult> {
  return requestJson<CodingAgentPatchResult>(
    projectUrl(serverUrl, projectId, `/roles/${encodeURIComponent(role)}/coding-agent/patches`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 记录编码智能体交接文档。
 * 作用域：当前操作者；场景：开发完成后沉淀实现说明、自测、接口、数据库和部署文档。
 */
export function recordCodingAgentHandoff(
  projectId: string,
  role: string,
  input: CodingAgentHandoffInput,
  serverUrl = matrixCodeServerUrl()
): Promise<DocumentSummary> {
  return requestJson<DocumentSummary>(
    projectUrl(serverUrl, projectId, `/roles/${encodeURIComponent(role)}/coding-agent/handoffs`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 生成产品需求草稿。
 * 作用域：项目成员；场景：产品输入原始需求后生成 PRD、界面说明和验收标准草稿。
 */
export function createProductDrafts(
  projectId: string,
  input: { requirement: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DocumentSummary[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DocumentSummary[]>(projectUrl(request.serverUrl, projectId, '/roles/product/drafts'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 冻结指定文档版本。
 * 作用域：项目成员；场景：产品确认 PRD 或交接文档后进入后续研发流转。
 */
export function freezeDocument(
  projectId: string,
  documentId: string,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DocumentSummary> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DocumentSummary>(
    projectUrl(request.serverUrl, projectId, `/documents/${encodeURIComponent(documentId)}/freeze`),
    { method: 'POST', headers: request.headers }
  );
}

/**
 * 提交开发交付材料。
 * 作用域：项目成员；场景：开发角色提交实现说明、自测结果、接口文档、数据库脚本和部署说明。
 */
export function submitDeveloperDelivery(
  projectId: string,
  input: {
    workspacePath: string;
    implementationNote: string;
    selfTestResult: string;
    apiDoc: string;
    databaseScript: string;
    deploymentDoc: string;
  },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DocumentSummary[]> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DocumentSummary[]>(projectUrl(request.serverUrl, projectId, '/roles/developer/deliveries'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 创建项目 Bug。
 * 作用域：项目成员；场景：测试或验收阶段记录缺陷、严重级别和当前责任角色。
 */
export function createBug(
  projectId: string,
  input: {
    title: string;
    severity: BugSeverity;
    steps: string;
    expected: string;
    actual: string;
    createdByRole: string;
    currentOwnerRole: string;
  },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectBug> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectBug>(projectUrl(request.serverUrl, projectId, '/bugs'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 推进 Bug 状态。
 * 作用域：项目成员；场景：缺陷确认、修复中、待回归、关闭或重新打开。
 */
export function transitionBug(
  projectId: string,
  bugId: string,
  input: { nextStatus: BugStatus; note: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ProjectBug> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ProjectBug>(projectUrl(request.serverUrl, projectId, `/bugs/${encodeURIComponent(bugId)}/transitions`), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 提交测试报告。
 * 作用域：项目成员；场景：测试角色沉淀验证结论和风险说明。
 */
export function submitTestReport(
  projectId: string,
  input: { report: string },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DocumentSummary> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DocumentSummary>(projectUrl(request.serverUrl, projectId, '/roles/tester/reports'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 配置部署目标。
 * 作用域：项目成员或项目管理角色，最终由服务端校验；场景：登记环境地址、健康检查和回滚说明。
 */
export function configureDeploymentTarget(
  projectId: string,
  input: {
    environmentName: string;
    environmentUrl: string;
    sshAddress: string;
    deployNote: string;
    healthCheckUrl: string;
    rollbackNote: string;
  },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DeploymentTarget> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DeploymentTarget>(projectUrl(request.serverUrl, projectId, '/deployments/targets'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}

/**
 * 对部署目标执行健康检查。
 * 作用域：当前操作者；场景：运维角色验证环境可用性并生成部署健康记录。
 */
export function runDeploymentHealthCheck(
  projectId: string,
  targetId: string,
  input: { actorId: string },
  serverUrl = matrixCodeServerUrl()
): Promise<DeploymentHealthCheck> {
  return requestJson<DeploymentHealthCheck>(
    projectUrl(serverUrl, projectId, `/deployments/targets/${encodeURIComponent(targetId)}/health-checks`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 记录部署或回滚操作。
 * 作用域：当前操作者；场景：上线、回滚或发布脚本导入前后的人工运维记录。
 */
export function recordDeploymentOperation(
  projectId: string,
  targetId: string,
  input: DeploymentOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<DeploymentOperationRecord> {
  return requestJson<DeploymentOperationRecord>(
    projectUrl(serverUrl, projectId, `/deployments/targets/${encodeURIComponent(targetId)}/operations`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 导入发布脚本审计日志。
 * 作用域：当前操作者；场景：把服务器侧发布、回滚脚本产生的 JSONL 审计导入项目部署记录。
 */
export function importDeploymentReleaseAudit(
  projectId: string,
  targetId: string,
  input: DeploymentReleaseAuditImportInput,
  serverUrl = matrixCodeServerUrl()
): Promise<DeploymentReleaseAuditImportResult> {
  return requestJson<DeploymentReleaseAuditImportResult>(
    projectUrl(serverUrl, projectId, `/deployments/targets/${encodeURIComponent(targetId)}/release-audit-imports`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 配置 Compose 演示环境。
 * 作用域：项目成员；场景：登记 compose 文件、工作区和环境变量用于演示或联调。
 */
export function configureComposeEnvironment(
  projectId: string,
  targetId: string,
  input: ComposeEnvironmentInput,
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<ComposeEnvironment> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<ComposeEnvironment>(
    projectUrl(request.serverUrl, projectId, `/deployments/targets/${encodeURIComponent(targetId)}/compose-environments`),
    {
      method: 'POST',
      headers: request.headers,
      body: JSON.stringify(input)
    }
  );
}

function executeComposeEnvironmentAction(
  projectId: string,
  environmentId: string,
  action: 'validate' | 'start' | 'stop' | 'logs',
  input: ComposeOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ComposeOperationRecord> {
  return requestJson<ComposeOperationRecord>(
    projectUrl(serverUrl, projectId, `/compose-environments/${encodeURIComponent(environmentId)}/${action}`),
    {
      method: 'POST',
      headers: actorHeaders(input.actorId),
      body: JSON.stringify(input)
    }
  );
}

/**
 * 校验 Compose 环境配置。
 * 作用域：当前操作者；场景：启动前检查 compose 文件和工作区是否可用。
 */
export function validateComposeEnvironment(
  projectId: string,
  environmentId: string,
  input: ComposeOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ComposeOperationRecord> {
  return executeComposeEnvironmentAction(projectId, environmentId, 'validate', input, serverUrl);
}

/**
 * 启动 Compose 环境。
 * 作用域：当前操作者；场景：运维或测试拉起本地演示环境。
 */
export function startComposeEnvironment(
  projectId: string,
  environmentId: string,
  input: ComposeOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ComposeOperationRecord> {
  return executeComposeEnvironmentAction(projectId, environmentId, 'start', input, serverUrl);
}

/**
 * 停止 Compose 环境。
 * 作用域：当前操作者；场景：演示、联调或测试结束后释放本地环境。
 */
export function stopComposeEnvironment(
  projectId: string,
  environmentId: string,
  input: ComposeOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ComposeOperationRecord> {
  return executeComposeEnvironmentAction(projectId, environmentId, 'stop', input, serverUrl);
}

/**
 * 采集 Compose 环境日志。
 * 作用域：当前操作者；场景：排查部署、测试或演示环境异常。
 */
export function captureComposeLogs(
  projectId: string,
  environmentId: string,
  input: ComposeOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<ComposeOperationRecord> {
  return executeComposeEnvironmentAction(projectId, environmentId, 'logs', input, serverUrl);
}

/**
 * 提交产品验收结论。
 * 作用域：项目成员；场景：产品确认通过或退回开发/测试，并生成验收文档。
 */
export function submitAcceptance(
  projectId: string,
  input: { accepted: boolean; note: string; returnToRole: '开发' | '测试' },
  actorUserIdOrServerUrl = matrixCodeServerUrl(),
  serverUrl?: string
): Promise<DocumentSummary> {
  const request = actorScopedRequest(actorUserIdOrServerUrl, serverUrl);
  return requestJson<DocumentSummary>(projectUrl(request.serverUrl, projectId, '/roles/product/acceptance'), {
    method: 'POST',
    headers: request.headers,
    body: JSON.stringify(input)
  });
}
