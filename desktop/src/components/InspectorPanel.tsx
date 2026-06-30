import { useEffect, useState } from 'react';

import type {
  AgentRunEventRecord,
  AgentRunRecord,
  AgentRunStatus,
  AgentRunUserAuditReport,
  ComposeEnvironment,
  ComposeEnvironmentStatus,
  ComposeOperationStatus,
  ComposeRuntimeView,
  DeploymentTarget,
  DeploymentTargetStatus,
  DeploymentHealthStatus,
  DeploymentOperationStatus,
  DeploymentRuntimeSummary,
  ExecutionTask,
  ExecutionTaskStatus,
  BugSeverity,
  BugStatus,
  LocalExecutionSummary,
  LocalTaskLogStream,
  ModelGatewaySummary,
  ModelCostTrendReport,
  ModelRunRequestPage,
  ModelRequestRecord,
  ProjectMember,
  ProjectBug,
  ProjectEvent,
  WorkbenchMetrics
} from '../api/client';
import { loadAgentRunModelRequests, loadProjectModelCostTrends } from '../api/client';
import type { RuntimeNotification } from '../runtimeNotifications';

type RuntimeNotificationFilter = 'all' | 'unread';
type AgentEventFilter = 'all' | 'tool' | 'failure';

type InspectorPanelProps = {
  projectId: string;
  actorUserId: string;
  agentRuns?: AgentRunRecord[];
  agentRunEvents?: AgentRunEventRecord[];
  agentRunUserAudit?: AgentRunUserAuditReport | null;
  projectMembers?: ProjectMember[];
  metrics: WorkbenchMetrics;
  bugs: ProjectBug[];
  deploymentTargets: DeploymentTarget[];
  deploymentRuntimeSummaries: DeploymentRuntimeSummary[];
  composeEnvironments: ComposeEnvironment[];
  composeRuntimeViews: ComposeRuntimeView[];
  events: ProjectEvent[];
  modelGateway: ModelGatewaySummary;
  localExecution: LocalExecutionSummary;
  runtimeNotifications?: RuntimeNotification[];
  runtimeNotificationFilter?: RuntimeNotificationFilter;
  runtimeNotificationUnreadCount?: number;
  runtimeNotificationActionBusy?: boolean;
  runtimeNotificationActionsEnabled?: boolean;
  approvalBusyTaskId?: string | null;
  cancelBusyTaskId?: string | null;
  retryBusyRunId?: string | null;
  claimBusyRunId?: string | null;
  claimNextBusy?: boolean;
  onRuntimeNotificationFilterChange?: (filter: RuntimeNotificationFilter) => void;
  onMarkAllRuntimeNotificationsRead?: () => Promise<void>;
  onDecideLocalCommandApproval: (taskId: string, decision: 'ALLOW' | 'DENY') => Promise<void>;
  onCancelLocalExecutionTask: (taskId: string) => Promise<void>;
  onRetryAgentRun?: (runId: string) => Promise<void>;
  onClaimAgentRun?: (runId: string) => Promise<void>;
  onClaimNextAgentRun?: () => Promise<void>;
};

const severityLabels: Record<BugSeverity, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  BLOCKER: '阻塞'
};

const bugStatusLabels: Record<BugStatus, string> = {
  NEW: '新建',
  CONFIRMED: '已确认',
  FIXING: '修复中',
  REGRESSION_PENDING: '待回归',
  CLOSED: '已关闭',
  REOPENED: '重新打开'
};

const deploymentStatusLabels: Record<DeploymentTargetStatus, string> = {
  NOT_CONFIGURED: '未配置',
  APPROVAL_PENDING: '待审批',
  RECORDED: '已记录',
  RELEASE_READY: '可发布',
  DEPLOYED: '已部署'
};

const deploymentHealthStatusLabels: Record<DeploymentHealthStatus, string> = {
  HEALTHY: '健康',
  UNHEALTHY: '非健康',
  UNREACHABLE: '不可达'
};

const deploymentOperationStatusLabels: Record<DeploymentOperationStatus, string> = {
  RECORDED: '已记录',
  SUCCEEDED: '成功',
  FAILED: '失败'
};

const composeStatusLabels: Record<ComposeEnvironmentStatus, string> = {
  CONFIGURED: '已配置',
  VALIDATED: '已校验',
  RUNNING: '运行中',
  STOPPED: '已停止',
  FAILED: '失败'
};

const composeOperationStatusLabels: Record<ComposeOperationStatus, string> = {
  SUCCEEDED: '成功',
  FAILED: '失败'
};

const executionTaskStatusLabels: Record<ExecutionTaskStatus, string> = {
  APPROVAL_PENDING: '待审批',
  DENIED: '已拒绝',
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELED: '已取消'
};

const taskLogStreamLabels: Record<LocalTaskLogStream, string> = {
  STDOUT: '输出',
  STDERR: '错误',
  SYSTEM: 'SYSTEM'
};

const agentRunStatusLabels: Record<AgentRunStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELED: '已取消'
};

const modelRoleLabels: Record<AgentRunRecord['roleKey'], string> = {
  PRODUCT: '产品',
  DEVELOPER: '开发',
  TESTER: '测试',
  OPERATIONS: '运维'
};

function formatModelRoleKey(roleKey: string) {
  return modelRoleLabels[roleKey as AgentRunRecord['roleKey']] ?? roleKey;
}

const projectMemberRoleLabels: Record<string, string> = {
  OWNER: '负责人',
  ADMIN: '管理员',
  MAINTAINER: '维护者',
  PRODUCT: '产品',
  DEVELOPER: '开发',
  TESTER: '测试',
  OPERATIONS: '运维',
  CURRENT_ACTOR: '当前操作者',
  SYSTEM_POLICY: '安全策略'
};

const fallbackApprovalRolePriority = ['OWNER', 'ADMIN', 'MAINTAINER'];

const responsibilitySourceLabels: Record<string, string> = {
  CLAIMED_WORKER: '认领 Worker',
  ROLE_MEMBER: '角色成员',
  RUN_ACTOR: '运行操作者'
};

type ApprovalResponsibilityIdentity = {
  userId: string;
  roleKey: string;
};

type ApprovalResponsibilitySummary = {
  userId: string;
  roleLabel: string;
  pendingCount: number;
  approvedCount: number;
  rejectedCount: number;
  pendingTasks: ExecutionTask[];
};

type AgentRunModelUsageSummary = {
  requestCount: number;
  cacheHitTokens: number;
  cacheMissInputTokens: number;
  outputTokens: number;
  cacheHitRate: number;
  estimatedCost: number;
  currency: string;
  cacheSource: string;
  cachePolicyId: string;
  volatileSuffixStrategy: string;
  promptPartitionPolicyId: string;
  promptPartitionFingerprint: string;
  stablePartitionCount: number;
  volatilePartitionCount: number;
};

/**
 * 按选中 Agent 运行聚合模型请求用量。
 *
 * 该聚合只使用服务端 recentRequests 中已经脱敏的用量与 trace 元数据，不读取 prompt、响应正文、
 * 向量召回正文、工具输出或供应商密钥，保证运行中心能展示成本闭环但不扩大敏感数据暴露面。
 */
function summarizeAgentRunModelUsage(requests: ModelRequestRecord[]): AgentRunModelUsageSummary | null {
  if (!requests.length) {
    return null;
  }

  const sortedRequests = [...requests].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
  const latestRequest = sortedRequests[0];
  const totals = requests.reduce(
    (summary, request) => ({
      cacheHitTokens: summary.cacheHitTokens + request.usage.cacheHitTokens,
      cacheMissInputTokens: summary.cacheMissInputTokens + request.usage.cacheMissInputTokens,
      outputTokens: summary.outputTokens + request.usage.outputTokens,
      estimatedCost: summary.estimatedCost + request.usage.estimatedCost
    }),
    { cacheHitTokens: 0, cacheMissInputTokens: 0, outputTokens: 0, estimatedCost: 0 }
  );
  const promptTokens = totals.cacheHitTokens + totals.cacheMissInputTokens;
  const cacheSources = new Set(requests.map((request) => request.usage.cacheSource ?? 'UNKNOWN'));
  const singleCacheSource = Array.from(cacheSources)[0] ?? 'UNKNOWN';

  return {
    requestCount: requests.length,
    cacheHitTokens: totals.cacheHitTokens,
    cacheMissInputTokens: totals.cacheMissInputTokens,
    outputTokens: totals.outputTokens,
    cacheHitRate: promptTokens === 0 ? 0 : totals.cacheHitTokens / promptTokens,
    estimatedCost: totals.estimatedCost,
    currency: latestRequest.usage.currency,
    cacheSource: cacheSources.size === 1 ? singleCacheSource : 'MIXED',
    cachePolicyId: latestRequest.usage.cachePolicyId ?? '无',
    volatileSuffixStrategy: latestRequest.usage.volatileSuffixStrategy ?? '无',
    promptPartitionPolicyId: latestRequest.usage.promptPartitionPolicyId ?? '',
    promptPartitionFingerprint: latestRequest.usage.promptPartitionFingerprint ?? '',
    stablePartitionCount: latestRequest.usage.stablePartitionCount ?? 0,
    volatilePartitionCount: latestRequest.usage.volatilePartitionCount ?? 0
  };
}

function executionTaskOccurredAt(task: ExecutionTask): number {
  const occurredAt = new Date(task.decidedAt ?? task.canceledAt ?? task.createdAt).getTime();
  return Number.isFinite(occurredAt) ? occurredAt : 0;
}

/**
 * 合并运行中任务和最近任务，并按任务 ID 去重。
 *
 * <p>同一条审批任务可能同时出现在 activeTasks 和 recentTasks 中，运行中心只应计数一次；
 * activeTasks 放在前面，用更实时的任务状态覆盖最近历史里的同 ID 记录。</p>
 */
function collectUniqueExecutionTasks(activeTasks: ExecutionTask[], recentTasks: ExecutionTask[]): ExecutionTask[] {
  const taskById = new Map<string, ExecutionTask>();
  [...activeTasks, ...recentTasks].forEach((task) => {
    if (!taskById.has(task.taskId)) {
      taskById.set(task.taskId, task);
    }
  });

  return Array.from(taskById.values()).sort((left, right) => executionTaskOccurredAt(right) - executionTaskOccurredAt(left));
}

function findActiveMember(projectMembers: ProjectMember[], userId: string): ProjectMember | undefined {
  return projectMembers.find((member) => member.status === 'ACTIVE' && member.userId === userId);
}

/**
 * 将用户 ID 转换为责任视图可展示的成员身份。
 *
 * <p>历史审批人可能已被移出项目，找不到 ACTIVE 成员时仍保留用户 ID，
 * 并标记为当前操作者，避免审计结果被静默丢弃。</p>
 */
function memberIdentity(projectMembers: ProjectMember[], userId: string): ApprovalResponsibilityIdentity {
  const member = findActiveMember(projectMembers, userId);
  return {
    userId,
    roleKey: member?.roleKey ?? 'CURRENT_ACTOR'
  };
}

/**
 * 解析待审批本地命令当前应由谁处理。
 *
 * <p>规则顺序是运维成员、项目管理成员、当前操作者。这样既符合本地执行代理
 * 由运维兜底的协作模型，也能在项目成员配置不完整时保持工作台可用。</p>
 */
function resolvePendingApprovalOwner(
  projectMembers: ProjectMember[],
  currentActorUserId: string
): ApprovalResponsibilityIdentity {
  const activeMembers = projectMembers.filter((member) => member.status === 'ACTIVE');

  // 本地命令审批优先归属运维；若项目未配置运维成员，则交给项目管理角色，再兜底当前操作者。
  const operationsMember = activeMembers.find((member) => member.roleKey === 'OPERATIONS');
  if (operationsMember) {
    return { userId: operationsMember.userId, roleKey: operationsMember.roleKey };
  }

  const managerMember = activeMembers.find((member) => fallbackApprovalRolePriority.includes(member.roleKey));
  if (managerMember) {
    return { userId: managerMember.userId, roleKey: managerMember.roleKey };
  }

  return memberIdentity(projectMembers, currentActorUserId);
}

function approvalResponsibilityKey(identity: ApprovalResponsibilityIdentity): string {
  return `${identity.userId}:${identity.roleKey}`;
}

/**
 * 获取或创建单个责任人的聚合行。
 *
 * <p>责任视图需要同时展示待审批、已批准、拒绝/安全拦截三类计数，
 * 该函数集中初始化默认值，避免渲染层处理缺省字段。</p>
 */
function ensureApprovalSummary(
  summaries: Map<string, ApprovalResponsibilitySummary>,
  identity: ApprovalResponsibilityIdentity
): ApprovalResponsibilitySummary {
  const key = approvalResponsibilityKey(identity);
  const existing = summaries.get(key);
  if (existing) {
    return existing;
  }

  const summary: ApprovalResponsibilitySummary = {
    userId: identity.userId,
    roleLabel: projectMemberRoleLabels[identity.roleKey] ?? identity.roleKey,
    pendingCount: 0,
    approvedCount: 0,
    rejectedCount: 0,
    pendingTasks: []
  };
  summaries.set(key, summary);
  return summary;
}

/**
 * 根据本地执行任务生成审批责任人视图。
 *
 * <p>待审批任务按当前责任规则归属；已决任务按真实审批人归属；
 * 安全策略拦截没有人工审批人，归入 SYSTEM_POLICY，方便运行中心回溯。</p>
 */
function buildApprovalResponsibilities(
  projectMembers: ProjectMember[],
  currentActorUserId: string,
  tasks: ExecutionTask[]
): ApprovalResponsibilitySummary[] {
  const summaries = new Map<string, ApprovalResponsibilitySummary>();
  const pendingOwner = resolvePendingApprovalOwner(projectMembers, currentActorUserId);

  tasks.forEach((task) => {
    if (task.status === 'APPROVAL_PENDING') {
      const summary = ensureApprovalSummary(summaries, pendingOwner);
      summary.pendingCount += 1;
      summary.pendingTasks.push(task);
      return;
    }

    if (task.safetyRejectionReason) {
      ensureApprovalSummary(summaries, { userId: 'SYSTEM_POLICY', roleKey: 'SYSTEM_POLICY' }).rejectedCount += 1;
      return;
    }

    if (task.approvalDecision === 'ALLOW' && task.approverId) {
      ensureApprovalSummary(summaries, memberIdentity(projectMembers, task.approverId)).approvedCount += 1;
      return;
    }

    if ((task.approvalDecision === 'DENY' || task.status === 'DENIED') && task.approverId) {
      ensureApprovalSummary(summaries, memberIdentity(projectMembers, task.approverId)).rejectedCount += 1;
    }
  });

  return Array.from(summaries.values()).sort((left, right) => {
    if (left.pendingCount !== right.pendingCount) {
      return right.pendingCount - left.pendingCount;
    }
    const leftHandled = left.approvedCount + left.rejectedCount;
    const rightHandled = right.approvedCount + right.rejectedCount;
    if (leftHandled !== rightHandled) {
      return rightHandled - leftHandled;
    }
    return left.userId.localeCompare(right.userId);
  });
}

function summarizeAgentRunModelUsageFromPage(page: ModelRunRequestPage): AgentRunModelUsageSummary | null {
  const latestRequest = [...page.requests].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))[0];
  if (!latestRequest) {
    return null;
  }
  return {
    requestCount: page.metrics.requestCount,
    cacheHitTokens: page.metrics.cacheHitTokens,
    cacheMissInputTokens: page.metrics.cacheMissInputTokens,
    outputTokens: page.metrics.outputTokens,
    cacheHitRate: page.metrics.cacheHitRate,
    estimatedCost: page.metrics.estimatedCost,
    currency: page.metrics.currency,
    cacheSource: latestRequest.usage.cacheSource ?? 'UNKNOWN',
    cachePolicyId: latestRequest.usage.cachePolicyId ?? '无',
    volatileSuffixStrategy: latestRequest.usage.volatileSuffixStrategy ?? '无',
    promptPartitionPolicyId: latestRequest.usage.promptPartitionPolicyId ?? '',
    promptPartitionFingerprint: latestRequest.usage.promptPartitionFingerprint ?? '',
    stablePartitionCount: latestRequest.usage.stablePartitionCount ?? 0,
    volatilePartitionCount: latestRequest.usage.volatilePartitionCount ?? 0
  };
}

function formatNumber(value: number) {
  return value.toLocaleString('zh-CN');
}

function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

function formatCost(value: number) {
  return value.toFixed(3);
}

type WorkbenchStatusBarProps = Pick<
  InspectorPanelProps,
  'agentRunEvents' | 'agentRuns' | 'metrics' | 'modelGateway' | 'runtimeNotificationUnreadCount'
>;

/**
 * 在主工作台底部展示一行动态运行状态。
 *
 * <p>作用域：主工作台首屏；场景：用户需要持续看到当前模型、运行指标和工作摘要，
 * 但不需要打开完整运行中心或占用右侧整列空间。</p>
 */
export function WorkbenchStatusBar({
  agentRunEvents = [],
  agentRuns = [],
  metrics,
  modelGateway,
  runtimeNotificationUnreadCount = 0
}: WorkbenchStatusBarProps) {
  const cachePercent = Math.round(metrics.cacheHitRate * 100);
  const currentBinding = modelGateway.bindings[0];
  const gatewayMetrics = modelGateway.metrics;
  const recentRequest = modelGateway.recentRequests[modelGateway.recentRequests.length - 1];
  const latestAgentRun = agentRuns[0];
  const latestAgentRunEvents = latestAgentRun
    ? agentRunEvents
        .filter((event) => event.runId === latestAgentRun.id)
        .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))
        .slice(0, 2)
    : [];
  const modelSummary = currentBinding ? `${currentBinding.providerId} / ${currentBinding.model}` : '暂无模型绑定';
  const agentSummary = latestAgentRun ? `${agentRunStatusLabels[latestAgentRun.status]} · ${latestAgentRun.goal}` : '暂无 Agent 运行';
  const recentCacheHitPercent = recentRequest ? Math.round(recentRequest.usage.cacheHitRate * 100) : null;
  const prefixSummary = recentRequest?.usage.stablePrefixHash ? `prefix ${recentRequest.usage.stablePrefixHash}` : 'prefix 无';
  const fullSummary = [
    agentSummary,
    latestAgentRun?.failureSummary ? `失败摘要 ${latestAgentRun.failureSummary}` : '',
    latestAgentRun?.retryable ? '恢复策略 可重试' : '',
    modelSummary,
    `请求 ${formatNumber(gatewayMetrics.requestCount)}`,
    `费用 ${formatNumber(gatewayMetrics.estimatedCost)} ${gatewayMetrics.currency}`,
    `缓存 ${cachePercent}%`,
    recentCacheHitPercent === null ? '' : `最近命中 ${recentCacheHitPercent}%`,
    prefixSummary,
    `会话 tokens ${formatNumber(metrics.sessionTokens)}`,
    `文档 ${formatNumber(metrics.documentCount)}`,
    `未关 Bug ${formatNumber(metrics.openBugCount)}`,
    `未读提醒 ${formatNumber(runtimeNotificationUnreadCount)}`
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <section aria-label="工作台底部状态" className="workbench-statusbar" title={fullSummary}>
      <div aria-label="当前模型" className="workbench-statusbar__group workbench-statusbar__group--model">
        <span className="workbench-statusbar__dot" aria-hidden="true" />
        <span className="workbench-statusbar__label">当前模型</span>
        <strong className="workbench-statusbar__value workbench-statusbar__value--accent">{modelSummary}</strong>
      </div>
      <span className="workbench-statusbar__divider" aria-hidden="true" />
      <div aria-label="Agent 运行" className="workbench-statusbar__group workbench-statusbar__group--agent">
        <span className="workbench-statusbar__label">Agent</span>
        <strong className="workbench-statusbar__value">{agentSummary}</strong>
        {latestAgentRun?.failureSummary ? (
          <span className="workbench-statusbar__item">失败摘要 {latestAgentRun.failureSummary}</span>
        ) : null}
        {latestAgentRun?.retryable ? <span className="workbench-statusbar__item">恢复策略 可重试</span> : null}
        {latestAgentRun?.retryOfRunId ? (
          <span className="workbench-statusbar__item">重试来源 {latestAgentRun.retryOfRunId}</span>
        ) : null}
        {latestAgentRunEvents.map((event) => (
          <span className="workbench-statusbar__item" key={event.id}>
            {formatEventTime(event)} · {event.eventTitle}
          </span>
        ))}
      </div>
      <span className="workbench-statusbar__divider" aria-hidden="true" />
      <div aria-label="运行指标" className="workbench-statusbar__group">
        <span className="workbench-statusbar__label">运行指标</span>
        <span className="workbench-statusbar__item">请求 {formatNumber(gatewayMetrics.requestCount)}</span>
        <span className="workbench-statusbar__item">
          费用 {formatNumber(gatewayMetrics.estimatedCost)} {gatewayMetrics.currency}
        </span>
        <span className="workbench-statusbar__item">缓存 {cachePercent}%</span>
        {recentCacheHitPercent === null ? null : (
          <span className="workbench-statusbar__item">最近命中 {recentCacheHitPercent}%</span>
        )}
        <span className="workbench-statusbar__item">{prefixSummary}</span>
      </div>
      <span className="workbench-statusbar__divider" aria-hidden="true" />
      <div aria-label="工作摘要" className="workbench-statusbar__group">
        <span className="workbench-statusbar__label">工作摘要</span>
        <span className="workbench-statusbar__item">会话 tokens {formatNumber(metrics.sessionTokens)}</span>
        <span className="workbench-statusbar__item">文档 {formatNumber(metrics.documentCount)}</span>
        <span className="workbench-statusbar__item">未关 Bug {formatNumber(metrics.openBugCount)}</span>
        <span className="workbench-statusbar__item">未读提醒 {formatNumber(runtimeNotificationUnreadCount)}</span>
      </div>
    </section>
  );
}

type ToolTracePayload = {
  toolName?: string;
  action?: string;
  status?: string;
  referenceId?: string;
  summary?: string;
  providerId?: string;
  modelName?: string;
  cacheSource?: string;
  stablePrefixHash?: string;
  cachePolicyId?: string;
  volatileSuffixStrategy?: string;
  promptPartitionPolicyId?: string;
  promptPartitionFingerprint?: string;
  stablePartitionCount?: number;
  volatilePartitionCount?: number;
};

/**
 * 解析 Agent Runtime 的 TOOL_TRACE 低敏 JSON payload。
 *
 * 历史事件或异常 payload 会回退为 null，由 UI 继续展示原文，避免一个坏事件阻塞运行中心。
 */
function parseToolTracePayload(event: AgentRunEventRecord): ToolTracePayload | null {
  if (event.eventType !== 'TOOL_TRACE') {
    return null;
  }

  try {
    const parsed = JSON.parse(event.eventPayload) as Record<string, unknown>;
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }

    const metadata =
      parsed.metadata && typeof parsed.metadata === 'object' ? (parsed.metadata as Record<string, unknown>) : {};
    const payload = {
      toolName: textField(parsed.toolName),
      action: textField(parsed.action),
      status: textField(parsed.status),
      referenceId: textField(parsed.referenceId),
      summary: textField(parsed.summary),
      providerId: textField(metadata.providerId),
      modelName: textField(metadata.modelName),
      cacheSource: textField(metadata.cacheSource),
      stablePrefixHash: textField(metadata.stablePrefixHash),
      cachePolicyId: textField(metadata.cachePolicyId),
      volatileSuffixStrategy: textField(metadata.volatileSuffixStrategy),
      promptPartitionPolicyId: textField(metadata.promptPartitionPolicyId),
      promptPartitionFingerprint: textField(metadata.promptPartitionFingerprint),
      stablePartitionCount: numberField(metadata.stablePartitionCount),
      volatilePartitionCount: numberField(metadata.volatilePartitionCount)
    };
    return Object.values(payload).some(Boolean) ? payload : null;
  } catch {
    return null;
  }
}

/**
 * 从未知 JSON 字段中提取可展示文本，过滤空字符串和非字符串值。
 */
function textField(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function numberField(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function AgentRunEventPayload({ event }: { event: AgentRunEventRecord }) {
  const toolTrace = parseToolTracePayload(event);

  if (!toolTrace) {
    return <>{event.eventPayload}</>;
  }

  return (
    <>
      {toolTrace.toolName ? <>工具 {toolTrace.toolName}</> : null}
      {toolTrace.action ? (
        <>
          <br />
          动作 {toolTrace.action}
        </>
      ) : null}
      {toolTrace.status ? (
        <>
          <br />
          状态 {toolTrace.status}
        </>
      ) : null}
      {toolTrace.referenceId ? (
        <>
          <br />
          引用 {toolTrace.referenceId}
        </>
      ) : null}
      {toolTrace.summary ? (
        <>
          <br />
          摘要 {toolTrace.summary}
        </>
      ) : null}
      {toolTrace.providerId || toolTrace.modelName || toolTrace.cacheSource || toolTrace.stablePrefixHash ? (
        <>
          <br />
          模型 {toolTrace.providerId ?? 'unknown'}/{toolTrace.modelName ?? 'unknown'} ·{' '}
          {toolTrace.cacheSource ?? 'UNKNOWN'} · prefix {toolTrace.stablePrefixHash ?? '无'}
        </>
      ) : null}
      {toolTrace.cachePolicyId || toolTrace.volatileSuffixStrategy ? (
        <>
          <br />
          缓存 {toolTrace.cachePolicyId ?? '无'} · {toolTrace.volatileSuffixStrategy ?? '无'}
        </>
      ) : null}
      {toolTrace.promptPartitionPolicyId || toolTrace.promptPartitionFingerprint ? (
        <>
          <br />
          分区 {toolTrace.promptPartitionPolicyId ?? '无'} · {toolTrace.promptPartitionFingerprint ?? '无'} · 稳定{' '}
          {formatNumber(toolTrace.stablePartitionCount ?? 0)} · 动态{' '}
          {formatNumber(toolTrace.volatilePartitionCount ?? 0)}
        </>
      ) : null}
    </>
  );
}

function formatEventTime(event: { occurredAt: string }) {
  const date = new Date(event.occurredAt);
  if (Number.isNaN(date.getTime())) {
    return '时间待同步';
  }

  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');

  return `${month}月${day}日 ${hour}:${minute}`;
}

function OperationsDetailPanel({
  projectId,
  actorUserId,
  metrics,
  bugs,
  deploymentTargets,
  deploymentRuntimeSummaries,
  composeEnvironments,
  composeRuntimeViews,
  events,
  modelGateway,
  localExecution,
  runtimeNotifications = [],
  runtimeNotificationFilter = 'all',
  runtimeNotificationUnreadCount = runtimeNotifications.filter((notification) => !notification.readAt).length,
  runtimeNotificationActionBusy = false,
  runtimeNotificationActionsEnabled = true,
  approvalBusyTaskId,
  cancelBusyTaskId,
  retryBusyRunId,
  claimBusyRunId,
  claimNextBusy = false,
  onRuntimeNotificationFilterChange,
  onMarkAllRuntimeNotificationsRead,
  onDecideLocalCommandApproval,
  onCancelLocalExecutionTask,
  onRetryAgentRun,
  onClaimAgentRun,
  onClaimNextAgentRun,
  agentRuns = [],
  agentRunEvents = [],
  agentRunUserAudit = null,
  projectMembers = []
}: InspectorPanelProps) {
  const cachePercent = Math.round(metrics.cacheHitRate * 100);
  const currentBinding = modelGateway.bindings[0];
  const gatewayMetrics = modelGateway.metrics;
  const recentRequest = modelGateway.recentRequests[modelGateway.recentRequests.length - 1];
  const currentWorkspace = localExecution.workspaces[0];
  const activeTasks = localExecution.activeTasks ?? [];
  const recentTasks = localExecution.recentTasks ?? [];
  const approvalResponsibilities = buildApprovalResponsibilities(
    projectMembers,
    actorUserId,
    collectUniqueExecutionTasks(activeTasks, recentTasks)
  );
  const pendingApprovalCount = approvalResponsibilities.reduce((sum, item) => sum + item.pendingCount, 0);
  const recentTaskLogs = localExecution.recentTaskLogs ?? [];
  const recentTask = recentTasks[0];
  const pendingTask =
    recentTasks.find((task) => task.status === 'APPROVAL_PENDING') ??
    activeTasks.find((task) => task.status === 'APPROVAL_PENDING');
  const activeTask =
    activeTasks.find((task) => task.status === 'RUNNING' || task.status === 'QUEUED') ?? activeTasks[0];
  const taskForDisplay = pendingTask ?? activeTask ?? recentTask;
  const approvalBusy = taskForDisplay ? approvalBusyTaskId === taskForDisplay.taskId : false;
  const cancelBusy = taskForDisplay ? cancelBusyTaskId === taskForDisplay.taskId : false;
  const taskLogs = taskForDisplay
    ? recentTaskLogs.filter((log) => log.taskId === taskForDisplay.taskId).slice(0, 3)
    : [];
  const canCancelTask = taskForDisplay?.status === 'QUEUED' || taskForDisplay?.status === 'RUNNING';
  const recentAudit = localExecution.recentAuditRecords[0];
  const changedFileCount = localExecution.recentGitDiff?.changedFiles.length ?? 0;
  const runtimeSummaryByTargetId = new Map(deploymentRuntimeSummaries.map((summary) => [summary.targetId, summary]));
  const composeRuntimeViewByEnvironmentId = new Map(composeRuntimeViews.map((view) => [view.environmentId, view]));
  const latestAgentRun = agentRuns[0];
  const hasQueuedAgentRun = agentRuns.some((run) => run.status === 'QUEUED');
  const [selectedAgentRunId, setSelectedAgentRunId] = useState<string | null>(null);
  const [agentRunRequestPage, setAgentRunRequestPage] = useState<ModelRunRequestPage | null>(null);
  const [agentRunRequestPageError, setAgentRunRequestPageError] = useState('');
  const selectedAgentRun = agentRuns.find((run) => run.id === selectedAgentRunId) ?? latestAgentRun;
  useEffect(() => {
    if (!selectedAgentRun) {
      setAgentRunRequestPage(null);
      setAgentRunRequestPageError('');
      return;
    }
    let disposed = false;
    setAgentRunRequestPage(null);
    setAgentRunRequestPageError('');
    void loadAgentRunModelRequests(projectId, selectedAgentRun.id, { page: 0, size: 20 }, actorUserId)
      .then((page) => {
        if (!disposed) {
          setAgentRunRequestPage(page);
        }
      })
      .catch(() => {
        if (!disposed) {
          setAgentRunRequestPage(null);
          setAgentRunRequestPageError('模型请求分页暂不可用');
        }
      });
    return () => {
      disposed = true;
    };
  }, [actorUserId, projectId, selectedAgentRun?.id]);
  const selectedAgentRunEvents = selectedAgentRun
    ? agentRunEvents.filter((event) => event.runId === selectedAgentRun.id)
    : [];
  const latestAgentRunEvents = latestAgentRun ? agentRunEvents.filter((event) => event.runId === latestAgentRun.id) : [];
  const linkedAgentRequest = selectedAgentRun
    ? [...modelGateway.recentRequests].reverse().find((request) => request.agentRunId === selectedAgentRun.id)
    : undefined;
  const selectedAgentRunPage = agentRunRequestPage?.agentRunId === selectedAgentRun?.id ? agentRunRequestPage : null;
  const selectedAgentRunRequests = selectedAgentRun
    ? (selectedAgentRunPage?.requests ?? modelGateway.recentRequests.filter((request) => request.agentRunId === selectedAgentRun.id))
    : [];
  const selectedAgentRunUsage =
    selectedAgentRunPage
      ? summarizeAgentRunModelUsageFromPage(selectedAgentRunPage) ?? summarizeAgentRunModelUsage(selectedAgentRunRequests)
      : summarizeAgentRunModelUsage(selectedAgentRunRequests);
  const latestTrendPoint = selectedAgentRunPage?.trend[selectedAgentRunPage.trend.length - 1];
  const auditModelRequest = linkedAgentRequest ?? recentRequest;
  const [agentEventFilter, setAgentEventFilter] = useState<AgentEventFilter>('all');
  const filteredAgentRunEvents = selectedAgentRunEvents.filter((event) => {
    if (agentEventFilter === 'tool') {
      return event.eventType === 'TOOL_TRACE';
    }
    if (agentEventFilter === 'failure') {
      return event.eventType.includes('FAILED');
    }
    return true;
  });
  const toolTraceCount = selectedAgentRunEvents.filter((event) => event.eventType === 'TOOL_TRACE').length;
  const failureEventCount = selectedAgentRunEvents.filter((event) => event.eventType.includes('FAILED')).length;
  const canRetrySelectedAgentRun = selectedAgentRun?.status === 'FAILED' && selectedAgentRun.retryable;
  const canClaimSelectedAgentRun = selectedAgentRun?.status === 'QUEUED';
  const retryBusy = selectedAgentRun ? retryBusyRunId === selectedAgentRun.id : false;
  const claimBusy = selectedAgentRun ? claimBusyRunId === selectedAgentRun.id : false;

  return (
    <section className="metric-panel">
      <h2 className="metric-title">运行指标</h2>
      <article className="metric-card" aria-label="Agent 运行">
        <p className="metric-card__label">Agent 运行</p>
        {latestAgentRun ? (
          <>
            <p className="metric-card__value metric-card__value--accent">
              {agentRunStatusLabels[latestAgentRun.status]} · {latestAgentRun.goal}
            </p>
            <ul className="metric-list">
              <li>
                <span>
                  {modelRoleLabels[latestAgentRun.roleKey]} · {latestAgentRun.agentKind} · {latestAgentRun.modelName}
                </span>
              </li>
              <li>
                <span>操作者 {latestAgentRun.actorUserId}</span>
              </li>
              <li>
                <span>运行总数 {agentRuns.length.toLocaleString('zh-CN')}</span>
              </li>
              {latestAgentRun.summary ? (
                <li>
                  <span>{latestAgentRun.summary}</span>
                </li>
              ) : null}
              {latestAgentRun.status === 'FAILED' ? (
                <li>
                  <span>恢复策略 {latestAgentRun.retryable ? '可重试' : '不可重试'}</span>
                </li>
              ) : null}
              {latestAgentRun.failureSummary ? (
                <li>
                  <span>失败摘要 {latestAgentRun.failureSummary}</span>
                </li>
              ) : null}
              {latestAgentRun.retryOfRunId ? (
                <li>
                  <span>重试来源 {latestAgentRun.retryOfRunId}</span>
                </li>
              ) : null}
            </ul>
            {latestAgentRunEvents.length ? (
              <ul className="task-log-list" aria-label="Agent 关键事件">
                {latestAgentRunEvents.slice(0, 3).map((event) => (
                  <li key={event.id}>
                    {formatEventTime(event)} · {event.eventTitle}
                  </li>
                ))}
              </ul>
            ) : null}
            {hasQueuedAgentRun ? (
              <div className="approval-actions" aria-label="Agent 队列操作">
                <button
                  className="primary-button approval-actions__button"
                  disabled={!onClaimNextAgentRun || claimNextBusy}
                  onClick={() => void onClaimNextAgentRun?.()}
                  type="button"
                >
                  {claimNextBusy ? '认领中' : '认领下一条'}
                </button>
              </div>
            ) : null}
          </>
        ) : (
          <p className="empty-state">暂无 Agent 运行</p>
        )}
      </article>
      {agentRuns.length ? (
        <article className="metric-card metric-card--wide" aria-label="Agent 运行选择">
          <div className="notification-toolbar">
            <div>
              <p className="metric-card__label">Agent 运行选择</p>
              <span className="notification-toolbar__count">
                当前 {selectedAgentRun?.id ?? '无'} · 共 {agentRuns.length.toLocaleString('zh-CN')} 次
              </span>
            </div>
          </div>
          <div className="agent-run-selector">
            {agentRuns.map((run) => (
              <button
                aria-pressed={selectedAgentRun?.id === run.id}
                className="agent-run-selector__button"
                key={run.id}
                onClick={() => setSelectedAgentRunId(run.id)}
                type="button"
              >
                <span className="agent-run-selector__title">
                  {agentRunStatusLabels[run.status]} · {run.goal}
                </span>
                <span className="agent-run-selector__meta">
                  {run.id} · {modelRoleLabels[run.roleKey]} · {run.modelName}
                </span>
              </button>
            ))}
          </div>
        </article>
      ) : null}
      <article className="metric-card metric-card--wide" aria-label="用户责任审计">
        <div className="notification-toolbar">
          <div>
            <p className="metric-card__label">用户责任审计</p>
            <span className="notification-toolbar__count">
              当前用户 {agentRunUserAudit?.userId ?? '未选择'}
            </span>
          </div>
        </div>
        {agentRunUserAudit ? (
          <>
            <ul className="metric-list">
              <li>
                <span>
                  运行 {agentRunUserAudit.totalRuns.toLocaleString('zh-CN')} · 活跃{' '}
                  {agentRunUserAudit.activeResponsibilities.toLocaleString('zh-CN')} · 模型请求{' '}
                  {agentRunUserAudit.modelRequestCount.toLocaleString('zh-CN')}
                </span>
              </li>
            </ul>
            {agentRunUserAudit.entries.length ? (
              <ul className="agent-audit-list">
                {agentRunUserAudit.entries.slice(0, 5).map((entry) => (
                  <li className="agent-audit-item" key={`${entry.runId}:${entry.responsibilitySource}`}>
                    <strong>
                      运行 {entry.runId} · {responsibilitySourceLabels[entry.responsibilitySource] ?? entry.responsibilitySource} ·{' '}
                      {agentRunStatusLabels[entry.status]}
                    </strong>
                    <span>目标 {entry.goal}</span>
                    <span>
                      角色 {modelRoleLabels[entry.roleKey]} · {entry.agentKind} · 工具 {entry.toolTraceCount.toLocaleString('zh-CN')} ·{' '}
                      模型请求 {entry.modelRequestCount.toLocaleString('zh-CN')}
                    </span>
                    {entry.lastEventTitle ? <span>最近事件 {entry.lastEventTitle}</span> : null}
                    {entry.lastModelRequestId ? <span>最近请求 {entry.lastModelRequestId}</span> : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="empty-state">暂无责任运行</p>
            )}
          </>
        ) : (
          <p className="empty-state">暂无责任运行</p>
        )}
      </article>
      <article className="metric-card" aria-label="模型网关">
        <p className="metric-card__label">当前模型</p>
        {currentBinding ? (
          <>
            <p className="metric-card__value metric-card__value--accent">{currentBinding.model}</p>
            <ul className="metric-list">
              <li>
                <span>供应商 {currentBinding.providerId}</span>
              </li>
              <li>
                <span>缓存命中 token {gatewayMetrics.cacheHitTokens.toLocaleString('zh-CN')}</span>
              </li>
              <li>
                <span>未命中输入 token {gatewayMetrics.cacheMissInputTokens.toLocaleString('zh-CN')}</span>
              </li>
              <li>
                <span>输出 token {gatewayMetrics.outputTokens.toLocaleString('zh-CN')}</span>
              </li>
              <li>
                <span>
                  估算费用 {gatewayMetrics.estimatedCost.toLocaleString('zh-CN')} {gatewayMetrics.currency}
                </span>
              </li>
            </ul>
          </>
        ) : (
          <p className="empty-state">暂无模型绑定</p>
        )}
      </article>
      <article className="metric-card" aria-label="模型上下文">
        <p className="metric-card__label">最近上下文</p>
        {gatewayMetrics.recentContextTypes.length ? (
          <ul className="metric-list">
            {gatewayMetrics.recentContextTypes.map((contextType) => (
              <li key={contextType}>
                <span>{contextType}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">暂无模型上下文</p>
        )}
        {recentRequest ? <p className="form-hint">最近请求：{recentRequest.answerSummary}</p> : null}
      </article>
      <article className="metric-card" aria-label="本地执行代理">
        <p className="metric-card__label">本地执行代理</p>
        {currentWorkspace ? (
          <>
            <p className="metric-card__value metric-card__value--accent">{currentWorkspace.name}</p>
            <ul className="metric-list">
              <li>
                <span>工作区 {currentWorkspace.rootPath}</span>
              </li>
              {taskForDisplay ? (
                <li>
                  <span>
                    最近命令 {executionTaskStatusLabels[taskForDisplay.status]} · {taskForDisplay.approvalDecision} ·{' '}
                    {taskForDisplay.command}
                  </span>
                </li>
              ) : null}
              <li>
                <span>Git diff 变更文件 {changedFileCount.toLocaleString('zh-CN')}</span>
              </li>
              {recentAudit ? (
                <li>
                  <span>
                    审计 {recentAudit.decision} · {recentAudit.summary}
                  </span>
                </li>
              ) : null}
            </ul>
            {pendingTask ? (
              <div className="approval-actions" aria-label="本地命令审批操作">
                <button
                  className="primary-button approval-actions__button"
                  disabled={approvalBusy}
                  onClick={() => void onDecideLocalCommandApproval(pendingTask.taskId, 'ALLOW')}
                  type="button"
                >
                  批准执行
                </button>
                <button
                  className="secondary-button approval-actions__button"
                  disabled={approvalBusy}
                  onClick={() => void onDecideLocalCommandApproval(pendingTask.taskId, 'DENY')}
                  type="button"
                >
                  拒绝
                </button>
              </div>
            ) : null}
            {canCancelTask && taskForDisplay ? (
              <div className="approval-actions" aria-label="本地任务取消操作">
                <button
                  className="secondary-button approval-actions__button"
                  disabled={cancelBusy}
                  onClick={() => void onCancelLocalExecutionTask(taskForDisplay.taskId)}
                  type="button"
                >
                  取消任务
                </button>
              </div>
            ) : null}
            {taskLogs.length ? (
              <ul className="task-log-list" aria-label="最近任务日志">
                {taskLogs.map((log) => (
                  <li key={log.id}>
                    {taskLogStreamLabels[log.stream]} · {log.content}
                  </li>
                ))}
              </ul>
            ) : null}
            {taskForDisplay?.approverId ? <p className="form-hint">审批人：{taskForDisplay.approverId}</p> : null}
            {taskForDisplay?.canceledBy ? <p className="form-hint">取消人：{taskForDisplay.canceledBy}</p> : null}
            {taskForDisplay?.cancelNote ? <p className="form-hint">取消说明：{taskForDisplay.cancelNote}</p> : null}
            {taskForDisplay?.safetyRejectionReason ? (
              <p className="form-hint">安全拒绝：{taskForDisplay.safetyRejectionReason}</p>
            ) : null}
          </>
        ) : (
          <p className="empty-state">暂无授权工作区</p>
        )}
      </article>
      <article className="metric-card metric-card--wide" aria-label="审批责任人视图">
        <div className="notification-toolbar">
          <div>
            <p className="metric-card__label">审批责任人</p>
            <span className="notification-toolbar__count">待审批 {pendingApprovalCount}</span>
          </div>
        </div>
        {approvalResponsibilities.length ? (
          <ul className="approval-responsibility-list">
            {approvalResponsibilities.map((summary) => (
              <li className="approval-responsibility-item" key={`${summary.userId}:${summary.roleLabel}`}>
                <div className="approval-responsibility-item__header">
                  <strong>
                    {summary.userId} · {summary.roleLabel}
                  </strong>
                  <div className="approval-responsibility-item__counts">
                    <span>待审批 {summary.pendingCount}</span>
                    <span>已处理 {summary.approvedCount}</span>
                    <span>拒绝/拦截 {summary.rejectedCount}</span>
                  </div>
                </div>
                {summary.pendingTasks.length ? (
                  <ul className="approval-task-list" aria-label={`${summary.userId} 待审批命令`}>
                    {summary.pendingTasks.slice(0, 3).map((task) => (
                      <li key={task.taskId}>
                        <strong>{task.command}</strong>
                        <span>
                          申请人 {task.actorId} · {executionTaskStatusLabels[task.status]} · {task.taskId}
                        </span>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">暂无审批责任</p>
        )}
      </article>
      <article className="metric-card" aria-label="运行态提醒列表">
        <div className="notification-toolbar">
          <div>
            <p className="metric-card__label">运行态提醒</p>
            <span className="notification-toolbar__count">未读 {runtimeNotificationUnreadCount}</span>
          </div>
          <div className="notification-toolbar__actions">
            <div className="notification-filter" aria-label="运行态提醒筛选">
              <button
                aria-pressed={runtimeNotificationFilter === 'all'}
                className="notification-filter__button"
                onClick={() => onRuntimeNotificationFilterChange?.('all')}
                type="button"
              >
                全部
              </button>
              <button
                aria-pressed={runtimeNotificationFilter === 'unread'}
                className="notification-filter__button"
                onClick={() => onRuntimeNotificationFilterChange?.('unread')}
                type="button"
              >
                未读
              </button>
            </div>
            <button
              className="secondary-button notification-toolbar__mark-read"
              disabled={
                !runtimeNotificationActionsEnabled ||
                runtimeNotificationUnreadCount === 0 ||
                runtimeNotificationActionBusy
              }
              onClick={() => void onMarkAllRuntimeNotificationsRead?.()}
              type="button"
            >
              全部已读
            </button>
          </div>
        </div>
        {runtimeNotifications.length ? (
          <ul className="notification-list">
            {runtimeNotifications.map((notification) => (
              <li
                className={`notification-list__item notification-list__item--${notification.level.toLowerCase()} ${
                  notification.readAt ? 'notification-list__item--read' : ''
                }`}
                key={notification.id}
              >
                <div className="notification-list__header">
                  <strong>{notification.title}</strong>
                  <small className="notification-list__state">{notification.readAt ? '已读' : '未读'}</small>
                </div>
                <span>{notification.message}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">
            {runtimeNotificationFilter === 'unread' ? '暂无未读提醒' : '暂无运行态提醒'}
          </p>
        )}
      </article>
      <article className="metric-card">
        <p className="metric-card__label">缓存策略</p>
        <p className="metric-card__value metric-card__value--accent">缓存命中率 {cachePercent}%</p>
      </article>
      <article className="metric-card">
        <p className="metric-card__label">会话 tokens（词元）</p>
        <p className="metric-card__value">{metrics.sessionTokens.toLocaleString('zh-CN')}</p>
      </article>
      <ul className="metric-list">
        <li>
          <span>事件数 {metrics.eventCount.toLocaleString('zh-CN')}</span>
        </li>
        <li>
          <span>文档数 {metrics.documentCount.toLocaleString('zh-CN')}</span>
        </li>
        <li>
          <span>未关闭 Bug 数（缺陷） {metrics.openBugCount.toLocaleString('zh-CN')}</span>
        </li>
      </ul>
      <article className="metric-card" aria-label="Bug 队列">
        <p className="metric-card__label">Bug 队列</p>
        {bugs.length ? (
          <ul className="metric-list">
            {bugs.map((bug) => (
              <li key={bug.id}>
                <span>
                  {bug.title} · {severityLabels[bug.severity]} · {bugStatusLabels[bug.status]} · 负责人：
                  {bug.currentOwnerRole}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">暂无 Bug（缺陷）</p>
        )}
      </article>
      <article className="metric-card" aria-label="部署状态">
        <p className="metric-card__label">部署状态</p>
        {deploymentTargets.length ? (
          <ul className="metric-list">
            {deploymentTargets.map((target) => {
              const summary = runtimeSummaryByTargetId.get(target.id);
              const latestHealthCheck = summary?.latestHealthCheck;
              const latestDeploymentOperation = summary?.latestDeploymentOperation;
              const latestRollbackOperation = summary?.latestRollbackOperation;
              const healthText = latestHealthCheck
                ? `健康：${deploymentHealthStatusLabels[latestHealthCheck.status]} · HTTP ${latestHealthCheck.httpStatus ?? '未返回'} · ${latestHealthCheck.durationMillis} ms`
                : '健康：暂无';
              const deploymentText = latestDeploymentOperation
                ? `部署：${deploymentOperationStatusLabels[latestDeploymentOperation.status]} · ${latestDeploymentOperation.note || '无说明'}`
                : '部署：暂无';
              const rollbackText = latestRollbackOperation
                ? `回滚：${deploymentOperationStatusLabels[latestRollbackOperation.status]} · ${latestRollbackOperation.note || '无说明'}`
                : '回滚：暂无';

              return (
                <li key={target.id}>
                  <span>
                    {target.environmentName} · {deploymentStatusLabels[target.status]} · 远程执行：
                    {target.remoteExecuted ? '已触发' : '未触发'}
                    <br />
                    {healthText}
                    <br />
                    {deploymentText}
                    <br />
                    {rollbackText}
                  </span>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="empty-state">暂无部署目标</p>
        )}
      </article>
      <article className="metric-card" aria-label="Compose 运行态">
        <p className="metric-card__label">Compose 运行态</p>
        {composeEnvironments.length ? (
          <ul className="metric-list">
            {composeEnvironments.map((environment) => {
              const view = composeRuntimeViewByEnvironmentId.get(environment.id);
              const latestOperation = view?.latestOperation;
              const operationText = latestOperation
                ? `最近操作：${composeOperationStatusLabels[latestOperation.status]} · ${latestOperation.summary || '无摘要'}`
                : '最近操作：暂无';

              return (
                <li key={environment.id}>
                  <span>
                    {environment.projectName} · {environment.serviceName} · {composeStatusLabels[environment.status]}
                    <br />
                    Compose 文件：{environment.composeFilePath}
                    <br />
                    {operationText}
                    {latestOperation?.logExcerpt ? (
                      <>
                        <br />
                        <span className="metric-log-excerpt">{latestOperation.logExcerpt}</span>
                      </>
                    ) : null}
                  </span>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="empty-state">暂无 Compose 演示环境</p>
        )}
      </article>
      <article className="metric-card" aria-label="关键事件">
        <p className="metric-card__label">关键事件</p>
        {events.length ? (
          <ul className="metric-list">
            {events.map((event) => (
              <li key={event.id}>
                <span>
                  {formatEventTime(event)} · {event.message}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">暂无事件</p>
        )}
      </article>
      <article className="metric-card" aria-label="Agent 审计详情">
        <p className="metric-card__label">Agent 审计详情</p>
        {selectedAgentRun ? (
          <>
            <ul className="metric-list">
              <li>
                <span>
                  运行 {selectedAgentRun.id} · {agentRunStatusLabels[selectedAgentRun.status]} ·{' '}
                  {selectedAgentRun.retryable ? '可重试' : '不可重试'}
                </span>
              </li>
              <li>
                <span>目标 {selectedAgentRun.goal}</span>
              </li>
              <li>
                <span>
                  操作者 {selectedAgentRun.actorUserId} · 角色 {modelRoleLabels[selectedAgentRun.roleKey]}
                </span>
              </li>
              <li>
                <span>
                  模型 {selectedAgentRun.providerId}/{selectedAgentRun.modelName} · {selectedAgentRun.agentKind}
                </span>
              </li>
              <li>
                <span>
                  工具事件 {toolTraceCount.toLocaleString('zh-CN')} · 失败事件{' '}
                  {failureEventCount.toLocaleString('zh-CN')}
                </span>
              </li>
              {selectedAgentRun.retryOfRunId ? (
                <li>
                  <span>
                    恢复 {selectedAgentRun.retryOfRunId} · {selectedAgentRun.retryable ? '可重试' : '不可重试'}
                  </span>
                </li>
              ) : null}
              {selectedAgentRun.summary ? (
                <li>
                  <span>摘要 {selectedAgentRun.summary}</span>
                </li>
              ) : null}
              {selectedAgentRun.failureSummary ? (
                <li>
                  <span>失败 {selectedAgentRun.failureSummary}</span>
                </li>
              ) : null}
              {auditModelRequest ? (
                <li>
                  <span>
                    模型请求 {auditModelRequest.requestId} ·{' '}
                    {linkedAgentRequest ? `已关联 ${linkedAgentRequest.agentRunId}` : '线索'} ·{' '}
                    {auditModelRequest.usage.cacheSource ?? 'UNKNOWN'} · prefix{' '}
                    {auditModelRequest.usage.stablePrefixHash ?? '无'}
                  </span>
                </li>
              ) : null}
              {selectedAgentRunUsage ? (
                <li>
                  <span>
                    运行模型用量 请求 {formatNumber(selectedAgentRunUsage.requestCount)} · 命中{' '}
                    {formatNumber(selectedAgentRunUsage.cacheHitTokens)} · 未命中{' '}
                    {formatNumber(selectedAgentRunUsage.cacheMissInputTokens)} · 输出{' '}
                    {formatNumber(selectedAgentRunUsage.outputTokens)} · 命中率{' '}
                    {formatPercent(selectedAgentRunUsage.cacheHitRate)} · 费用 {selectedAgentRunUsage.currency}{' '}
                    {formatCost(selectedAgentRunUsage.estimatedCost)}
                  </span>
                </li>
              ) : null}
              {selectedAgentRunUsage ? (
                <li>
                  <span>
                    运行缓存策略 {selectedAgentRunUsage.cachePolicyId} ·{' '}
                    {selectedAgentRunUsage.volatileSuffixStrategy} · 来源 {selectedAgentRunUsage.cacheSource}
                  </span>
                </li>
              ) : null}
              {selectedAgentRunUsage?.promptPartitionPolicyId ? (
                <li>
                  <span>
                    Prompt分区 {selectedAgentRunUsage.promptPartitionPolicyId} ·{' '}
                    {selectedAgentRunUsage.promptPartitionFingerprint || '无'} · 稳定{' '}
                    {formatNumber(selectedAgentRunUsage.stablePartitionCount)} · 动态{' '}
                    {formatNumber(selectedAgentRunUsage.volatilePartitionCount)}
                  </span>
                </li>
              ) : null}
              {selectedAgentRunPage ? (
                <li>
                  <span>
                    请求分页 共 {formatNumber(selectedAgentRunPage.total)} · 第 {formatNumber(selectedAgentRunPage.page + 1)}
                    {' '}页 · 每页 {formatNumber(selectedAgentRunPage.size)}
                  </span>
                </li>
              ) : null}
              {latestTrendPoint ? (
                <li>
                  <span>
                    成本趋势 {latestTrendPoint.requestId} · 命中率 {formatPercent(latestTrendPoint.cacheHitRate)} ·{' '}
                    {latestTrendPoint.currency} {formatCost(latestTrendPoint.estimatedCost)} ·{' '}
                    {latestTrendPoint.cacheSource}
                  </span>
                </li>
              ) : null}
              {agentRunRequestPageError ? (
                <li>
                  <span>{agentRunRequestPageError}</span>
                </li>
              ) : null}
            </ul>
            {canRetrySelectedAgentRun || canClaimSelectedAgentRun ? (
              <div className="approval-actions" aria-label="Agent 运行操作">
                {canRetrySelectedAgentRun ? (
                  <button
                    className="secondary-button approval-actions__button"
                    disabled={!onRetryAgentRun || retryBusy}
                    onClick={() => void onRetryAgentRun?.(selectedAgentRun.id)}
                    type="button"
                  >
                    {retryBusy ? '排队中' : '重试运行'}
                  </button>
                ) : null}
                {canClaimSelectedAgentRun ? (
                  <button
                    className="secondary-button approval-actions__button"
                    disabled={!onClaimAgentRun || claimBusy}
                    onClick={() => void onClaimAgentRun?.(selectedAgentRun.id)}
                    type="button"
                  >
                    {claimBusy ? '认领中' : '认领运行'}
                  </button>
                ) : null}
              </div>
            ) : null}
          </>
        ) : (
          <p className="empty-state">暂无 Agent 审计详情</p>
        )}
      </article>
      <article className="metric-card" aria-label="Agent 运行时间线">
        <div className="notification-toolbar">
          <div>
            <p className="metric-card__label">Agent 运行时间线</p>
            <span className="notification-toolbar__count">
              事件 {filteredAgentRunEvents.length}/{selectedAgentRunEvents.length}
            </span>
          </div>
          <div className="notification-filter" aria-label="Agent 事件筛选">
            <button
              aria-pressed={agentEventFilter === 'all'}
              className="notification-filter__button"
              onClick={() => setAgentEventFilter('all')}
              type="button"
            >
              全部
            </button>
            <button
              aria-pressed={agentEventFilter === 'tool'}
              className="notification-filter__button"
              onClick={() => setAgentEventFilter('tool')}
              type="button"
            >
              工具
            </button>
            <button
              aria-pressed={agentEventFilter === 'failure'}
              className="notification-filter__button"
              onClick={() => setAgentEventFilter('failure')}
              type="button"
            >
              失败
            </button>
          </div>
        </div>
        {recentRequest ? (
          <p className="form-hint">
            模型请求线索 {recentRequest.requestId} · {recentRequest.model}
          </p>
        ) : null}
        {filteredAgentRunEvents.length ? (
          <ul className="metric-list">
            {filteredAgentRunEvents.map((event) => (
              <li key={event.id}>
                <span>
                  {formatEventTime(event)} · {event.eventTitle}
                  <br />
                  <AgentRunEventPayload event={event} />
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">
            {selectedAgentRunEvents.length ? '暂无匹配 Agent 运行事件' : '暂无 Agent 运行事件'}
          </p>
        )}
      </article>
    </section>
  );
}

export function InspectorPanel({
  projectId,
  actorUserId,
  metrics,
  events,
  modelGateway,
  runtimeNotifications = [],
  runtimeNotificationUnreadCount = runtimeNotifications.filter((notification) => !notification.readAt).length,
  agentRuns = [],
  agentRunEvents = []
}: InspectorPanelProps) {
  const cachePercent = Math.round(metrics.cacheHitRate * 100);
  const currentBinding = modelGateway.bindings[0];
  const gatewayMetrics = modelGateway.metrics;
  const recentRequest = modelGateway.recentRequests[modelGateway.recentRequests.length - 1];
  const recentEvents = events.slice(0, 4);
  const latestAgentRun = agentRuns[0];
  const [modelCostTrend, setModelCostTrend] = useState<ModelCostTrendReport | null>(null);
  useEffect(() => {
    let disposed = false;
    void loadProjectModelCostTrends(projectId, 30, actorUserId)
      .then((report) => {
        if (!disposed) {
          setModelCostTrend(report);
        }
      })
      .catch(() => {
        if (!disposed) {
          setModelCostTrend(null);
        }
      });
    return () => {
      disposed = true;
    };
  }, [actorUserId, gatewayMetrics.requestCount, projectId]);
  const costTrendMetrics = modelCostTrend?.metrics ?? gatewayMetrics;
  const latestDailyTrend = modelCostTrend?.dailyTrend[modelCostTrend.dailyTrend.length - 1];
  const topRoleBreakdown = modelCostTrend?.roleBreakdown[0];

  return (
    <section className="metric-panel">
      <h2 className="metric-title">运行指标</h2>
      <article className="metric-card" aria-label="Agent 运行">
        <p className="metric-card__label">Agent 运行</p>
        {latestAgentRun ? (
          <>
            <p className="metric-card__value metric-card__value--accent">
              {agentRunStatusLabels[latestAgentRun.status]} · {latestAgentRun.goal}
            </p>
            <ul className="metric-list">
              <li>
                <span>运行总数 {agentRuns.length.toLocaleString('zh-CN')}</span>
              </li>
              <li>
                <span>
                  {modelRoleLabels[latestAgentRun.roleKey]} · {latestAgentRun.modelName} · {latestAgentRun.actorUserId}
                </span>
              </li>
              {latestAgentRun.status === 'FAILED' ? (
                <li>
                  <span>恢复策略 {latestAgentRun.retryable ? '可重试' : '不可重试'}</span>
                </li>
              ) : null}
              {latestAgentRun.failureSummary ? (
                <li>
                  <span>失败摘要 {latestAgentRun.failureSummary}</span>
                </li>
              ) : null}
              {latestAgentRun.retryOfRunId ? (
                <li>
                  <span>重试来源 {latestAgentRun.retryOfRunId}</span>
                </li>
              ) : null}
              {agentRunEvents.slice(0, 2).map((event) => (
                <li key={event.id}>
                  <span>
                    {formatEventTime(event)} · {event.eventTitle}
                  </span>
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p className="empty-state">暂无 Agent 运行</p>
        )}
      </article>
      <article className="metric-card" aria-label="模型网关">
        <p className="metric-card__label">当前模型</p>
        {currentBinding ? (
          <>
            <p className="metric-card__value metric-card__value--accent">{currentBinding.model}</p>
            <ul className="metric-list">
              <li>
                <span>供应商 {currentBinding.providerId}</span>
              </li>
              <li>
                <span>请求数 {gatewayMetrics.requestCount.toLocaleString('zh-CN')}</span>
              </li>
              <li>
                <span>估算费用 {gatewayMetrics.estimatedCost.toLocaleString('zh-CN')} {gatewayMetrics.currency}</span>
              </li>
              {modelCostTrend ? (
                <>
                  <li>
                    <span>
                      30天费用 {costTrendMetrics.estimatedCost.toLocaleString('zh-CN')} {costTrendMetrics.currency}
                    </span>
                  </li>
                  <li>
                    <span>30天请求 {costTrendMetrics.requestCount.toLocaleString('zh-CN')}</span>
                  </li>
                </>
              ) : null}
              {latestDailyTrend ? (
                <li>
                  <span>
                    最近日 {latestDailyTrend.date} · {latestDailyTrend.metrics.requestCount.toLocaleString('zh-CN')} 次
                  </span>
                </li>
              ) : null}
              {topRoleBreakdown ? (
                <li>
                  <span>
                    主要角色 {formatModelRoleKey(topRoleBreakdown.key)} ·{' '}
                    {topRoleBreakdown.metrics.estimatedCost.toLocaleString('zh-CN')} {topRoleBreakdown.metrics.currency}
                  </span>
                </li>
              ) : null}
              {recentRequest?.usage.cacheSource ? (
                <li>
                  <span>缓存来源 {recentRequest.usage.cacheSource}</span>
                </li>
              ) : null}
              {recentRequest?.usage.cachePolicyId ? (
                <li>
                  <span>缓存策略 {recentRequest.usage.cachePolicyId}</span>
                </li>
              ) : null}
              {recentRequest?.usage.volatileSuffixStrategy ? (
                <li>
                  <span>volatile {recentRequest.usage.volatileSuffixStrategy}</span>
                </li>
              ) : null}
              {recentRequest ? (
                <li>
                  <span>最近命中率 {Math.round(recentRequest.usage.cacheHitRate * 100)}%</span>
                </li>
              ) : null}
              {recentRequest?.usage.stablePrefixHash ? (
                <li>
                  <span>prefix {recentRequest.usage.stablePrefixHash}</span>
                </li>
              ) : null}
            </ul>
          </>
        ) : (
          <p className="empty-state">暂无模型绑定</p>
        )}
      </article>
      <article className="metric-card" aria-label="运行摘要">
        <p className="metric-card__label">工作台摘要</p>
        <ul className="metric-list">
          <li>
            <span>缓存命中率 {cachePercent}%</span>
          </li>
          <li>
            <span>会话 tokens（词元） {metrics.sessionTokens.toLocaleString('zh-CN')}</span>
          </li>
          <li>
            <span>文档数 {metrics.documentCount.toLocaleString('zh-CN')}</span>
          </li>
          <li>
            <span>未关闭 Bug 数（缺陷） {metrics.openBugCount.toLocaleString('zh-CN')}</span>
          </li>
          <li>
            <span>未读提醒 {runtimeNotificationUnreadCount.toLocaleString('zh-CN')}</span>
          </li>
        </ul>
      </article>
      <article className="metric-card" aria-label="关键事件">
        <p className="metric-card__label">关键事件</p>
        {recentEvents.length ? (
          <ul className="metric-list">
            {recentEvents.map((event) => (
              <li key={event.id}>
                <span>
                  {formatEventTime(event)} · {event.message}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="empty-state">暂无事件</p>
        )}
      </article>
    </section>
  );
}

export function OperationsCenterDialog({
  open,
  onClose,
  ...panelProps
}: InspectorPanelProps & {
  open: boolean;
  onClose: () => void;
}) {
  if (!open) {
    return null;
  }

  return (
    <div className="config-dialog-backdrop">
      <section aria-label="运行中心" className="config-dialog operations-dialog" role="dialog">
        <header className="config-dialog__header">
          <div>
            <p className="eyebrow">运行控制</p>
            <h2>运行中心</h2>
          </div>
          <button aria-label="关闭运行中心" className="config-dialog__close" onClick={onClose} type="button">
            ×
          </button>
        </header>
        <OperationsDetailPanel {...panelProps} />
      </section>
    </div>
  );
}
